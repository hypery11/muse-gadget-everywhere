"""Configured household connections. Credentials never enter command results."""
from __future__ import annotations

import asyncio
import json
import re
import ssl
import threading
import urllib.error
import urllib.parse
import urllib.request


def http_url(value):
    if not isinstance(value, str) or len(value) > 4096:
        raise ValueError("invalid URL")
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("use an HTTP(S) URL without embedded credentials")
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("redirect refused; configure the final server URL")


class MediaRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        http_url(newurl)
        if req.full_url.startswith("https:") and not newurl.startswith("https:"):
            raise ValueError("HTTPS downgrade refused")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class HomeAssistant:
    def __init__(self, settings, emit):
        self.settings, self.emit = settings, emit
        self.connected = False
        self.last_error = "not configured"
        self.opener = urllib.request.build_opener(NoRedirect())

    def configuration(self):
        settings = self.settings()
        base = settings.get("ha_url", "").rstrip("/")
        token = settings.get("ha_token", "")
        if not base or not token:
            raise ValueError("configure Home Assistant URL and token on the device")
        http_url(base)
        parsed = urllib.parse.urlsplit(base)
        if parsed.query or parsed.fragment:
            raise ValueError("Home Assistant URL cannot contain query or fragment")
        return base, token

    def request(self, path, body=None):
        base, token = self.configuration()
        req = urllib.request.Request(base + "/api/" + path,
                                     data=None if body is None else json.dumps(body, allow_nan=False).encode(),
                                     headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
        try:
            with self.opener.open(req, timeout=12) as response:
                raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise ValueError("Home Assistant response exceeds 1 MiB")
            return json.loads(raw)
        except urllib.error.HTTPError as exc:
            raise ValueError(f"Home Assistant returned HTTP {exc.code}") from None
        except (OSError, json.JSONDecodeError):
            raise ValueError("Home Assistant is unreachable or returned invalid JSON") from None

    def run(self, command, params):
        if command == "home.status":
            try:
                self.configuration()
                configured = True
            except ValueError:
                configured = False
            return {"configured": configured, "events_connected": self.connected,
                    "actions_enabled": bool(self.settings().get("ha_actions", False)), "detail": self.last_error}
        if command == "home.states":
            entity = params.get("entity_id", "")
            if entity and not re.fullmatch(r"[a-z0-9_]+\.[a-z0-9_]+", entity):
                raise ValueError("invalid entity_id")
            return {"states": self.request("states" + ("/" + entity if entity else ""))}
        if not self.settings().get("ha_actions", False):
            raise ValueError("enable Home Assistant actions locally first")
        domain, service = params["domain"], params["service"]
        if not all(re.fullmatch(r"[a-z0-9_]{1,80}", v) for v in (domain, service)):
            raise ValueError("invalid domain or service")
        entities = [x.strip() for x in params["entity_id"].split(",")]
        if not 1 <= len(entities) <= 20 or not all(re.fullmatch(r"[a-z0-9_]+\.[a-z0-9_]+", x) for x in entities):
            raise ValueError("explicit entity IDs required; wildcard targets are not allowed")
        data = dict(params.get("data", {}))
        if any(k in data for k in ("entity_id", "target", "area_id", "device_id", "floor_id", "label_id")):
            raise ValueError("put targets only in entity_id")
        result = self.request(f"services/{domain}/{service}", {**data, "entity_id": entities})
        return {"status": "accepted", "changed_states": result,
                "verification": "Home Assistant accepted the action; physical completion depends on the integration"}

    async def watch(self, stop):
        from websockets.asyncio.client import connect
        class DirectConnection(connect):
            def process_redirect(self, exc):
                # Never send the configured token to a redirected WS endpoint.
                return exc

        while not stop.is_set():
            try:
                base, token = self.configuration()
                if not self.settings().get("ha_events", False):
                    raise ValueError("event subscription disabled")
                url = ("wss" if base.startswith("https") else "ws") + base[base.index(":"):] + "/api/websocket"
                async with DirectConnection(url, open_timeout=10, max_size=1024 * 1024, proxy=None) as ws:
                    hello = json.loads(await asyncio.wait_for(ws.recv(), 10))
                    if hello.get("type") != "auth_required":
                        raise ValueError("unexpected Home Assistant handshake")
                    await ws.send(json.dumps({"type": "auth", "access_token": token}))
                    auth = json.loads(await asyncio.wait_for(ws.recv(), 10))
                    if auth.get("type") != "auth_ok":
                        raise ValueError("Home Assistant authentication rejected")
                    await ws.send(json.dumps({"id": 1, "type": "subscribe_events", "event_type": "state_changed"}))
                    subscribed = json.loads(await asyncio.wait_for(ws.recv(), 10))
                    if not subscribed.get("success"):
                        raise ValueError("Home Assistant subscription rejected")
                    self.connected, self.last_error = True, "subscribed"
                    async for raw in ws:
                        item = json.loads(raw)
                        data = item.get("event", {}).get("data", {})
                        entity = data.get("entity_id", "")
                        selected = [x.strip() for x in self.settings().get("ha_entities", "").split(",") if x.strip()]
                        if selected and entity not in selected:
                            continue
                        if item.get("type") == "event" and entity:
                            state = data.get("new_state") or {}
                            self.emit("home.state", {"entity_id": entity, "state": state.get("state")})
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                # Deliberately don't stringify network exceptions containing URLs.
                self.last_error = str(exc) if isinstance(exc, ValueError) else "connection unavailable"
            finally:
                self.connected = False
            try:
                await asyncio.wait_for(stop.wait(), 15)
            except asyncio.TimeoutError:
                pass


class Mqtt:
    def __init__(self, settings, emit):
        self.settings, self.emit = settings, emit
        self.client = None
        self.connected = False
        self.detail = "not configured"
        self.lock = threading.Lock()

    def start(self):
        cfg = self.settings()
        host, prefix = cfg.get("mqtt_host", ""), cfg.get("mqtt_prefix", "muse")
        if not host:
            return
        if not re.fullmatch(r"[A-Za-z0-9.:-]{1,253}", host) or not re.fullmatch(r"[A-Za-z0-9_/-]{1,100}", prefix):
            self.detail = "invalid host or topic prefix"
            return
        try:
            import paho.mqtt.client as mqtt
            client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)
            if cfg.get("mqtt_user"):
                client.username_pw_set(cfg["mqtt_user"], cfg.get("mqtt_password", ""))
            if cfg.get("mqtt_tls", False):
                client.tls_set_context(ssl.create_default_context())

            def connected(client, userdata, flags, reason, properties):
                self.connected = reason == 0
                self.detail = "connected" if self.connected else "connection rejected"
                if self.connected:
                    client.subscribe(prefix.rstrip("/") + "/#", qos=1)

            def disconnected(client, userdata, flags, reason, properties):
                self.connected, self.detail = False, "disconnected"

            def received(client, userdata, message):
                if len(message.payload) <= 4096:
                    self.emit("mqtt.message", {"topic": message.topic,
                                              "payload": message.payload.decode(errors="replace"),
                                              "retained": bool(message.retain)})

            client.on_connect, client.on_disconnect, client.on_message = connected, disconnected, received
            client.reconnect_delay_set(1, 60)
            client.max_queued_messages_set(20)
            client.connect_async(host, int(cfg.get("mqtt_port", 8883 if cfg.get("mqtt_tls") else 1883)), 60)
            client.loop_start()
            self.client, self.detail = client, "connecting"
        except Exception:
            self.detail = "MQTT initialization failed"

    def run(self, command, params):
        cfg = self.settings()
        if command == "mqtt.status":
            return {"configured": bool(cfg.get("mqtt_host")), "connected": self.connected,
                    "publishing_enabled": bool(cfg.get("mqtt_publish", False)), "detail": self.detail}
        if not cfg.get("mqtt_publish", False):
            raise ValueError("enable MQTT publishing locally first")
        topic, payload = params["topic"], params["payload"]
        prefix = cfg.get("mqtt_prefix", "muse").rstrip("/") + "/"
        if not topic.startswith(prefix) or any(x in topic for x in ("#", "+", "\x00")) or len(topic) > 256:
            raise ValueError("topic must be beneath the configured prefix without wildcards")
        if len(payload.encode()) > 4096:
            raise ValueError("payload exceeds 4 KiB")
        if not self.connected or self.client is None:
            raise ValueError("MQTT is not connected")
        with self.lock:
            info = self.client.publish(topic, payload, qos=1, retain=False)
            info.wait_for_publish(timeout=8)
            if not info.is_published():
                raise ValueError("broker acknowledgement timed out")
        return {"status": "broker_acknowledged", "topic": topic}

    def close(self):
        if self.client:
            self.client.disconnect()
            self.client.loop_stop()
        self.connected = False
