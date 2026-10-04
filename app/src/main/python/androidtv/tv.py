"""tv.* commands: Muse controls the dongle it lives on.

tv.launch starts apps via intents (no root: starting exported activities is a
normal app capability). tv.cast drives Cast receivers with PyChromecast,
defaulting to this dongle's own address (Cast-to-self) but accepting any LAN
host, so the gadget doubles as a Cast remote.

PyChromecast ships in the pip block; the import stays lazy so a broken
install degrades to a readable error instead of killing the service.
"""

from __future__ import annotations

TV_COMMAND_SPECS = {
    "tv.launch": {
        "description": (
            "Launch an app on this Android TV by package name "
            "(com.google.android.youtube.tv) or open a URL / intent URI."
        ),
        "required": {
            "target": {"type": "string", "description": "Package name, URL or intent URI."},
        },
        "optional": {},
    },
    "tv.cast": {
        "description": (
            "Control a Google Cast receiver: read status, play/pause/stop, set "
            "volume, or play a media URL. Defaults to this dongle itself."
        ),
        "timeout_ms": 60000,
        "required": {
            "action": {
                "type": "string",
                "description": "One of: status, play, pause, stop, volume, play_url.",
            },
        },
        "optional": {
            "host": {"type": "string", "description": "Receiver IPv4. Default: this dongle."},
            "value": {"type": "number", "description": "Volume 0.0-1.0 for the volume action."},
            "url": {"type": "string", "description": "Media URL for play_url."},
            "mime": {"type": "string", "description": "MIME type for play_url. Default video/mp4."},
            "stream": {"type": "string", "description": "BUFFERED (VOD, default) or LIVE."},
        },
    },
}


def run_tv(tv_control, command: str, params: dict) -> dict:
    from musegadget.executor import error, ok

    if tv_control is None:
        return error("tv commands need the Android bridge")
    try:
        if command == "tv.launch":
            return run_launch(tv_control, params)
        if command == "tv.cast":
            return run_cast(tv_control, params)
    except Exception as exc:
        return error(f"{type(exc).__name__}: {exc}")
    return error(f"unsupported command: {command}")


def _bridge_ok(result) -> bool:
    return bool(result.getOk())


def _bridge_message(result) -> str:
    return str(result.getMessage())


def run_launch(tv_control, params: dict) -> dict:
    from musegadget.executor import error, ok

    target = params.get("target")
    if not isinstance(target, str) or not target.strip():
        return error("target is required")
    result = tv_control.launch(target.strip())
    if _bridge_ok(result):
        return ok({"result": _bridge_message(result)})
    return error(_bridge_message(result))


def _is_started(state: dict) -> bool:
    """True once the receiver reports a real player state.

    BUFFERING counts as started (the load was accepted); UNKNOWN/IDLE
    with no title means the load died or never landed.
    """
    return state.get("player") not in (None, "UNKNOWN", "IDLE")


def _await_media_state(media_controller, tries: int = 4, gap_s: float = 2.0) -> dict:
    """Poll media status until the receiver reports a real player state.

    Returns the last seen ``{"player": ..., "title": ...}``.
    """
    import time

    state = {"player": None, "title": None}
    for _ in range(tries):
        try:
            status = media_controller.status
        except Exception:
            break
        state = {
            "player": getattr(status, "player_state", None),
            "title": getattr(status, "title", None),
        }
        if _is_started(state):
            break
        time.sleep(gap_s)
    return state


def run_cast(tv_control, params: dict) -> dict:
    from musegadget.executor import error, ok

    try:
        import pychromecast
    except ImportError:
        return error("PyChromecast is not installed in this build; reinstall the APK")

    action = params.get("action")
    if action not in ("status", "play", "pause", "stop", "volume", "play_url"):
        return error(f"unknown cast action: {action!r}")

    host = params.get("host")
    if not host:
        ip = tv_control.deviceIp()
        if not _bridge_ok(ip):
            return error(f"no target host: {_bridge_message(ip)}")
        host = _bridge_message(ip)
    import uuid

    cast = None
    try:
        try:
            cast = pychromecast.get_chromecast_from_host(
                (host, 8009, uuid.uuid4(), None, None)
            )
            cast.wait(timeout=10)
        except Exception as exc:
            return error(f"unreachable Cast receiver at {host}: {exc}")
        if action == "status":
            cast.wait(timeout=5)
            status = cast.status
            media = cast.media_controller.status if cast.media_controller else None
            return ok({
                "host": host,
                "volume": round(status.volume_level, 2),
                "muted": status.volume_muted,
                "app": status.display_name,
                "player": getattr(media, "player_state", None),
                "title": getattr(media, "title", None),
            })
        if action in ("play", "pause", "stop"):
            getattr(cast.media_controller, action)()
            return ok({"host": host, "action": action})
        if action == "volume":
            try:
                level = float(params.get("value"))
            except (TypeError, ValueError):
                return error("volume needs value 0.0-1.0")
            level = max(0.0, min(1.0, level))
            cast.set_volume(level)
            return ok({"host": host, "volume": level})
        url = params.get("url")
        if not isinstance(url, str) or not url:
            return error("play_url needs url")
        # pychromecast defaults streamType to LIVE; a VOD file loaded as
        # LIVE never leaves IDLE. Default to BUFFERED, allow LIVE.
        stream = (params.get("stream") or "BUFFERED").upper()
        if stream not in ("BUFFERED", "LIVE"):
            return error(f"unknown stream type: {stream!r} (want BUFFERED or LIVE)")
        mime = params.get("mime") or "video/mp4"
        # A LOAD into a lingering session plays without taking the
        # foreground (invisible playback). Quit first so every play_url
        # starts a fresh, visible receiver session; a quit failure just
        # means nothing was running.
        try:
            cast.quit_app()
        except Exception:
            pass
        cast.media_controller.play_media(url, mime, stream_type=stream)
        # play_media is fire-and-forget: a rejected load (cleartext http,
        # dead URL) still returns without raising. Worse, pychromecast
        # sends LOAD as soon as the launch is ACKed, while the receiver
        # web app is still booting — that LOAD is silently dropped and the
        # receiver sits at IDLE. Poll for a real player state; if the
        # first LOAD raced boot, send it once more at a ready receiver.
        state = _await_media_state(cast.media_controller)
        if not _is_started(state):
            cast.media_controller.play_media(url, mime, stream_type=stream)
            state = _await_media_state(cast.media_controller)
        if not _is_started(state):
            return error(f"receiver did not start playback (player={state['player']})")
        return ok({"host": host, "playing": url, **state})
    finally:
        if cast is not None:
            try:
                cast.disconnect(timeout=5)
            except Exception:
                pass
