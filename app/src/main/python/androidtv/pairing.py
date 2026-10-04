"""Pairing entry points, called from PairActivity on a worker thread.

Mirrors `musegadget pair` with the transport injected: on Debian that is the
BlueZ server, here it is the Kotlin GATT server. Pairing crypto, framing and
provisioning logic are upstream and untouched.
"""

from __future__ import annotations

import threading
import time
from typing import Callable

from androidtv.env import configure


def get_ble_name(files_dir: str) -> str:
    configure(files_dir)
    from musegadget import identity

    return identity.load_or_create().ble_name


def is_paired(files_dir: str) -> bool:
    configure(files_dir)
    from musegadget import config

    return config.load_json(config.PAIRING_FILE) is not None


def reset_pairing(files_dir: str) -> None:
    """Forget the Muse account binding, keep everything else.

    Mirrors upstream ``cmd_unpair`` exactly (``config.delete_json`` on the
    pairing file only): identity and SDK token survive, so the BLE name
    stays stable across re-pairs and no token re-import is needed.
    """
    configure(files_dir)
    from musegadget import config

    from androidtv.cloud import PAIRING_LOCK
    with PAIRING_LOCK:
        config.delete_json(config.PAIRING_FILE)


def save_sdk_token(files_dir: str, token: str) -> None:
    configure(files_dir)
    from musegadget import config

    token = token.strip()
    if not token:
        raise ValueError("empty SDK token")
    # Validate against upstream's own shape (not a local copy): if the
    # shape ever changes we follow it, or fail loudly here per UPSTREAM.md.
    if not config._SDK_TOKEN.fullmatch(token):
        raise ValueError(
            f"SDK token has wrong shape ({len(token)} chars); "
            "copy it again from gadgets.muse.ai"
        )
    path = config.state_dir() / config.SDK_TOKEN_FILE
    config.state_dir().mkdir(mode=0o700, parents=True, exist_ok=True)
    import os
    import tempfile
    fd, temporary = tempfile.mkstemp(prefix=".sdk-token-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            output.write(token)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def get_sdk_token(files_dir: str) -> str | None:
    configure(files_dir)
    from musegadget import config
    return config.sdk_token()


class _TransportAdapter:
    """Bridge Chaquopy's Java conversion gap for chunked sends.

    Chaquopy cannot convert a Python ``list`` to ``java.util.List``, so a
    direct ``transport.send_packets(list)`` raises ``TypeError`` and every
    setup reply crashes. This adapter satisfies upstream's transport
    protocol by looping the native ``send_packet(bytes)`` instead; the
    stagger pacing mirrors ``CHUNK_STAGGER_S`` from ``ble_framing``.
    """

    def __init__(self, native) -> None:
        self._native = native

    def send_packets(self, packets) -> None:
        import logging

        from musegadget.ble_framing import CHUNK_STAGGER_S

        log = logging.getLogger("androidtv.pairing")
        packets = list(packets)
        for i, packet in enumerate(packets):
            if i:
                time.sleep(CHUNK_STAGGER_S)
            if not self._native.send_packet(bytes(packet)):
                log.warning("setup reply packet %d/%d dropped", i + 1, len(packets))

    def mtu(self) -> int:
        return int(self._native.mtu())

    def attach_controller(self, controller) -> None:
        class _BytesBoundary:
            """Convert Chaquopy jarray to real bytes before upstream sees it.

            Java hands RX values over as ``jarray('B')``. Bulk ``bytes()``
            conversion preserves every octet, but scalar indexing returns
            SIGNED values, so a chunked frame's magic ``0xFE`` reads as -2
            and upstream's ``packet[0] != CHUNK_MAGIC`` check misfires —
            every chunked inbound message dies as "invalid command JSON".
            Normalising here keeps upstream untouched.
            """

            def on_write(self, value) -> None:
                controller.on_write(bytes(value))

            def __getattr__(self, name):
                # on_disconnect and any future controller hooks pass through.
                return getattr(controller, name)

        self._native.attach_controller(_BytesBoundary())

    def disconnect(self, delay: float = 0.0) -> None:
        self._native.disconnect(float(delay))

    def shutdown(self) -> None:
        self._native.shutdown()


def run_pairing(transport, files_dir: str, sdk_token: str | None, timeout_s: int = 600) -> bool:
    """Open setup until the app pairs or the window closes. Blocking."""
    import logging

    configure(files_dir)
    from musegadget import __version__, config, identity, muse_api
    from musegadget.ble_setup import Credentials, ProvisionFailed, SetupController
    from musegadget.pairing import PairingSession

    log = logging.getLogger("androidtv.pairing")

    def verify_and_save(credentials: Credentials, commit: Callable[[Callable[[], bool]], bool]) -> None:
        api_url = credentials.api_url if credentials.api_url.startswith("https://") else ""
        api_url_v2 = credentials.api_url_v2 if credentials.api_url_v2.startswith("https://") else ""
        vms, status = muse_api.fetch_vms_with_status(
            credentials.access_token, muse_api.api_root(api_url_v2),
        )
        if not vms:
            log.warning("device token check failed (HTTP %s, %d VMs)", status, len(vms))
            raise ProvisionFailed("auth_failed")
        record = {
            "access_token": credentials.access_token,
            "refresh_token": credentials.refresh_token,
            "token_type": "device",
            "username": credentials.username,
            "api_url": api_url,
            "api_url_v2": api_url_v2,
            "noise_host": credentials.noise_host,
            "access_token_saved_at": int(time.time()),
        }

        def save() -> bool:
            config.save_json(config.PAIRING_FILE, record)
            return True

        if not commit(save):
            raise ProvisionFailed("error_storage")

    from musegadget import network as upstream_network

    ident = identity.load_or_create()
    pairing = PairingSession(
        node_id=ident.node_id,
        device_id=ident.device_id,
        mac=ident.mac,
        firmware_version=__version__,
        sdk_token=sdk_token,
    )
    completed = threading.Event()
    native = transport
    transport = _TransportAdapter(transport)
    controller = SetupController(
        pairing=pairing,
        identity=ident,
        version=__version__,
        transport=transport,
        network=upstream_network,
        provision=verify_and_save,
        on_complete=completed.set,
    )
    transport.attach_controller(controller)
    window = threading.Timer(timeout_s, transport.shutdown)
    window.daemon = True
    log.info("setup open for %ds as %s", timeout_s, ident.ble_name)
    controller.start()
    window.start()
    try:
        deadline = time.monotonic() + timeout_s
        while not completed.wait(0.2):
            if not native.is_open() or time.monotonic() >= deadline:
                break
    finally:
        window.cancel()
        if completed.is_set():
            # Mirror upstream's 1.5s shutdown delay so the phone reads auth_ok
            # before we tear down the transport.
            time.sleep(1.5)
        controller.stop()
    return completed.is_set()
