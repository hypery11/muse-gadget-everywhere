"""The command interface shared by Muse, on-device controls and automations."""
from __future__ import annotations

import base64
import json
import math
import threading
import time
import urllib.request
from pathlib import Path

from musegadget.executor import error, ok
from androidtv.commands import SPECS, catalog, validate
from androidtv.events import EventStore
from androidtv.integrations import HomeAssistant, MediaRedirect, Mqtt, http_url


class Runtime:
    def __init__(self, executor, native=None, store=None):
        self.executor, self.native = executor, native
        self.account = executor.account
        state = Path(self.account.home) / "musegadget"
        state.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.store = store or EventStore(state / "runtime.sqlite")
        self.home = HomeAssistant(self.settings, self.emit)
        self.mqtt = Mqtt(self.settings, self.emit)
        self.executor.developer_enabled = lambda: bool(self.settings().get("developer", False))
        self.state = {"state": "starting"}
        self._locks = {"cast": threading.Lock(), "automation": threading.RLock(), "download": threading.Lock()}
        self._event_inputs = []
        self._event_lock = threading.Lock()
        self._last_event = {}
        self._lifecycle = threading.Condition()
        self._active_commands = 0
        self._closing = False

    def settings(self):
        return json.loads(str(self.native.settings())) if self.native is not None else {}

    def specs(self):
        return catalog(self.executor.developer_enabled())

    def native_call(self, command, params):
        if self.native is None:
            return error("this command requires the Android runtime")
        return json.loads(str(self.native.invoke(command, json.dumps(params, allow_nan=False))))

    def emit(self, kind, data=None, notify=None, ttl_s=300):
        # Suppress identical bursts (sensor callbacks and reconnect duplicates).
        key = kind + json.dumps(data or {}, sort_keys=True)
        now = time.monotonic()
        with self._event_lock:
            if now - self._last_event.get(key, -100) < 2:
                return {"status": "deduplicated"}
            if len(self._last_event) > 200:
                self._last_event.clear()
            self._last_event[key] = now
            requested = notify if notify is not None else kind in {"home.state", "mqtt.message", "battery.low", "power.changed", "media.ended", "screen.action"}
            event = self.store.emit(kind, data, requested and not (data or {}).get("retained", False) and self.settings().get("notify_muse", False), ttl_s)
            if len(self._event_inputs) < 100:
                self._event_inputs.append(event)
            return event

    def tick(self):
        if self.native is not None:
            for item in json.loads(str(self.native.drainEvents())):
                self.emit(item["type"], item.get("data", {}), item.get("notify"))
        with self._event_lock:
            events, self._event_inputs = self._event_inputs, []
        rules = self.store.due()
        for event in events:
            # Retained MQTT snapshots are not new physical events.
            if not event["data"].get("retained", False):
                rules.extend(self.store.due(event))
        for rule in rules:
            self._scene(rule)

    def _scene(self, rule):
        with self._locks["automation"]:
            results = []
            for action in rule["actions"]:
                result = self.run(action["command"], action.get("params", {}))
                results.append({"command": action["command"], **result})
                if not result.get("ok"):
                    break
            return {"id": rule["id"], "completed": len(results) == len(rule["actions"]) and all(r["ok"] for r in results),
                    "results": results}

    def run(self, command, params, timeout_ms=None):
        with self._lifecycle:
            if self._closing:
                return error("runtime is stopping")
            self._active_commands += 1
        try:
            return self._execute(command, params, timeout_ms)
        finally:
            with self._lifecycle:
                self._active_commands -= 1
                self._lifecycle.notify_all()

    def _execute(self, command, params, timeout_ms=None):
        started = time.monotonic()
        try:
            if command == "system.run" and not self.executor.developer_enabled():
                raise ValueError("system.run is disabled; enable trusted developer mode on the device")
            validate(command, params, self.specs())
            if timeout_ms is not None and (isinstance(timeout_ms, bool) or not isinstance(timeout_ms, (int, float)) or not math.isfinite(timeout_ms) or timeout_ms <= 0):
                raise ValueError("timeout_ms must be positive")
            result = self._run(command, params, timeout_ms)
            if len(json.dumps(result, allow_nan=False).encode()) > 220 * 1024:
                result = error("result exceeds link budget; narrow the query")
        except Exception as exc:
            result = error(str(exc) if isinstance(exc, (ValueError, TimeoutError)) else f"{type(exc).__name__}: command failed")
        self.store.audit(command, result.get("ok", False), int((time.monotonic() - started) * 1000))
        return result

    def _run(self, command, params, timeout_ms):
        if command == "device.status":
            return ok({**self.state, "events": self.store.recent(), "commands": self.store.history()})
        if command == "device.capabilities":
            native = self.native_call(command, params) if self.native is not None else ok({})
            if not native["ok"]:
                return native
            import platform
            import ssl
            import cryptography
            from cryptography.hazmat.backends import default_backend
            return ok({**native["payload"], "runtime": {
                           "python": platform.python_version(), "openssl": ssl.OPENSSL_VERSION,
                           "cryptography": cryptography.__version__, "crypto_openssl": default_backend().openssl_version_text()},
                       "workspace": str(self.executor.workspace.root),
                       "developer_mode": self.executor.developer_enabled(), "commands": sorted(self.specs()),
                       "home": self.home.run("home.status", {}), "mqtt": self.mqtt.run("mqtt.status", {})})
        if command.startswith("home."):
            return ok(self.home.run(command, params))
        if command.startswith("mqtt."):
            return ok(self.mqtt.run(command, params))
        if command == "events.emit":
            return ok(self.emit(params["type"], params.get("data"), params.get("notify", False), params.get("ttl_s", 300)))
        if command == "events.list":
            return ok({"events": self.store.recent()})
        if command == "automation.list":
            return ok({"automations": self.store.rules()})
        if command == "automation.put":
            return ok(self.store.put_rule(params, lambda cmd, p: validate(cmd, p, self.specs())))
        if command == "automation.delete":
            return ok({"deleted": self.store.delete_rule(params["id"])})
        if command == "automation.run":
            rule = next((r for r in self.store.rules() if r["id"] == params["id"]), None)
            if not rule:
                raise ValueError("unknown automation")
            if not rule.get("enabled", True):
                raise ValueError("automation is disabled")
            outcome = self._scene(rule)
            return ok(outcome) if outcome["completed"] else {"ok": False, "error": "scene stopped at a failed action", "payload": outcome}
        if command == "cast.discover":
            result = self.native_call(command, params)
            if result["ok"]:
                self.store.remember_devices(result["payload"].get("devices", []))
            return result
        if command == "cast.devices":
            return ok({"devices": self.store.devices()})
        if command == "cast.name":
            return ok(self.store.name_device(params["id"], params["name"], params.get("room", "")))
        if command == "tv.cast":
            params = dict(params)
            if "device" in params:
                selected = params.pop("device")
                matches = [d for d in self.store.devices() if selected in (d["id"], d.get("name"), d.get("alias"), d.get("room"))]
                if len(matches) != 1:
                    raise ValueError("receiver name is missing or ambiguous; use cast.devices and its ID")
                params["host"] = matches[0]["host"]
            if not self._locks["cast"].acquire(timeout=min((timeout_ms or 60000) / 1000, 10)):
                raise TimeoutError("another Cast operation is still running")
            try:
                return self.executor.run(command, params, timeout_ms)
            finally:
                self._locks["cast"].release()
        if command == "media.cache":
            return ok(self.cache(params, timeout_ms))
        if command in SPECS:
            return self.native_call(command, params)
        return self.executor.run(command, params, timeout_ms)

    def cache(self, params, timeout_ms=None):
        url = http_url(params["url"])
        self.executor.workspace.parts(params["path"])
        opener = urllib.request.build_opener(MediaRedirect())
        duration = min(100, timeout_ms / 1000) if timeout_ms is not None else 100
        deadline, offset = time.monotonic() + duration, 0
        if not self._locks["download"].acquire(timeout=min(10, duration)):
            raise TimeoutError("another download is still running")
        try:
            with opener.open(url, timeout=min(15, duration)) as response:
                length = response.headers.get("Content-Length")
                expected = int(length) if length is not None else None
                if expected is not None and not 0 <= expected <= 64 * 1024 * 1024:
                    raise ValueError("download exceeds 64 MiB or has invalid length")
                while True:
                    if time.monotonic() >= deadline:
                        raise TimeoutError("download deadline exceeded")
                    data = response.read(65536)
                    if not data and expected is not None and offset != expected:
                        raise ValueError("incomplete download; partial file discarded")
                    result = self.executor.workspace.write({"path": params["path"], "offset": offset,
                        "data_b64": base64.b64encode(data).decode(), "create_parents": True, "final": not data})
                    offset += len(data)
                    if not data:
                        return result
        except Exception:
            self.executor.workspace.discard_partial(params["path"])
            raise
        finally:
            self._locks["download"].release()

    def close(self):
        with self._lifecycle:
            self._closing = True
            self._lifecycle.wait_for(lambda: self._active_commands == 0)
        self.mqtt.close()
        self.store.close()
