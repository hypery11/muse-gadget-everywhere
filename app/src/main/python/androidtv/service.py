"""Android lifecycle adapter for upstream, with real status and graceful stop."""
from __future__ import annotations

import asyncio
import json
import threading
import time
import uuid

_guard = threading.Lock()
_loop = None
_service = None
_runtime = None
_stop_requested = False


def prepare_start():
    global _stop_requested
    with _guard:
        _stop_requested = False


def stop():
    global _stop_requested
    with _guard:
        _stop_requested = True
        if _loop is not None and _service is not None:
            _loop.call_soon_threadsafe(_service.stop)


def invoke(command: str, params_json: str) -> str:
    with _guard:
        runtime = _runtime
    if runtime is None:
        return json.dumps({"ok": False, "error": "Start the gadget service first"})
    return json.dumps(runtime.run(command, json.loads(params_json)), allow_nan=False)


def main(files_dir: str, cache_dir: str, display_name: str, native=None) -> None:
    from androidtv.env import configure
    configure(files_dir, cache_dir)
    from musegadget import config, identity
    from androidtv.cloud import AndroidService as Service
    from androidtv.compat import AndroidExecutor, android_account
    from androidtv.runtime import Runtime
    import musegadget.executor as upstream_executor

    runtime = Runtime(AndroidExecutor(android_account(files_dir), native), native)
    # Preserve the dict reference imported by upstream service, without changing
    # any vendor file or the homehub/Linux protocol identity.
    upstream_executor.COMMAND_SPECS.clear()
    upstream_executor.COMMAND_SPECS.update(runtime.specs())
    try:
        sdk_token = config.sdk_token()
    except ValueError:
        sdk_token = None
    service = Service(identity=identity.load_or_create(), executor=runtime,
                      sdk_token=sdk_token, display_name=display_name)
    try:
        asyncio.run(_serve(service, runtime))
    finally:
        runtime.close()


async def _serve(service, runtime):
    global _loop, _service, _runtime
    from musegadget import config
    with _guard:
        _loop, _service, _runtime = asyncio.get_running_loop(), service, runtime
        if _stop_requested:
            service.stop()
    server = None
    tasks = []
    try:
        server = await service.serve_local(config.socket_path())
        runtime.mqtt.start()
        tasks = [asyncio.create_task(_observe(service, runtime)),
                 asyncio.create_task(_tick(service, runtime)),
                 asyncio.create_task(runtime.home.watch(service._stop))]
        await service.run()
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        if server:
            server.close()
            await server.wait_closed()
        _update(runtime, "stopped")
        with _guard:
            _runtime, _service, _loop = None, None, None


def _update(runtime, state):
    runtime.state = {"state": state, "updated_at": int(time.time())}
    if runtime.native is not None:
        runtime.native.updateState(json.dumps(runtime.state))


async def _observe(service, runtime):
    from musegadget import config
    last_delivery = 0.0
    # The SDK accepts a human label, but Muse's chat endpoint requires a UUID.
    # Keep one stable side-chat per device across reconnects and app restarts.
    event_session = str(uuid.uuid5(uuid.NAMESPACE_URL, f"muse-gadget:{service.identity.node_id}:events"))
    while not service._stop.is_set():
        try:
            session = service._current
            if not config.load_json(config.PAIRING_FILE):
                state = "unpaired"
            elif session is not None and session.registered_at is not None:
                state = "connected"
            elif session is not None:
                state = "connecting"
            else:
                state = "reconnecting"
            _update(runtime, state)
            pending = runtime.store.pending()
            if pending and state == "connected" and time.monotonic() - last_delivery >= 5 and runtime.settings().get("notify_muse", False):
                last_delivery = time.monotonic()
                # Stable event ID lets the recipient recognize an ambiguous retry.
                # Network acknowledgements do not guarantee exactly-once delivery.
                message = json.dumps({"event_id": pending["id"], "type": pending["type"],
                                      "data": json.loads(pending["data"])})
                try:
                    reply = await session.send_chat(message, event_session)
                    accepted = bool(reply.get("ok"))
                    runtime.store.delivered(pending["id"], accepted,
                                            None if accepted else f"HTTP {reply.get('status', 'unknown')}")
                except Exception as exc:
                    runtime.store.delivered(pending["id"], False, type(exc).__name__)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            import logging
            logging.getLogger(__name__).warning("observer recovered from %s", type(exc).__name__)
        try:
            await asyncio.wait_for(service._stop.wait(), 1)
        except asyncio.TimeoutError:
            pass


async def _tick(service, runtime):
    while not service._stop.is_set():
        try:
            await asyncio.to_thread(runtime.tick)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            import logging
            logging.getLogger(__name__).warning("event worker recovered from %s", type(exc).__name__)
        try:
            await asyncio.wait_for(service._stop.wait(), 1)
        except asyncio.TimeoutError:
            pass
