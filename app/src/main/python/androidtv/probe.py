"""Android TV overlay: probe entry point (build 0.1)."""

from __future__ import annotations


def probe() -> str:
    import sys

    import musegadget
    import websockets
    from cryptography import __version__ as crypto_version

    return (
        f"python={sys.version.split()[0]} "
        f"musegadget={musegadget.__version__} "
        f"cryptography={crypto_version} "
        f"websockets={websockets.__version__}"
    )


def probe_tv(tv_control) -> str:
    """Exercise the Java/Python TvResult boundary without side effects."""
    from androidtv.tv import _bridge_message, _bridge_ok

    ip = tv_control.deviceIp()
    launch = tv_control.launch("com.example.does.not.exist.tv")
    return (
        f"deviceIp ok={_bridge_ok(ip)} msg={_bridge_message(ip)[:28]} | "
        f"launch ok={_bridge_ok(launch)} msg={_bridge_message(launch)[:48]}"
    )


def probe_demo(tv_control) -> str:
    """Side-effecting demo: open YouTube, then read our own Cast status."""
    import logging

    from androidtv.tv import run_tv

    log = logging.getLogger("androidtv.probe")
    launched = run_tv(tv_control, "tv.launch", {"target": "com.google.android.youtube.tv"})
    log.warning("demo tv.launch -> %s", launched)
    status = run_tv(tv_control, "tv.cast", {"action": "status"})
    log.warning("demo tv.cast -> %s", status)
    return f"launch={launched} cast={status}"
