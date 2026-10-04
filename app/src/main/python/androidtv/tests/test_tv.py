"""Overlay tests. Run from the repo root on any machine with the submodule:

    PYTHONPATH=vendor/muse-gadget-sdk/linux/src:app/src/main/python \
        python3 -m pytest app/src/main/python/androidtv/tests/ -q
"""

from __future__ import annotations

import sys

import pytest

sys.path.insert(0, "vendor/muse-gadget-sdk/linux/src")
sys.path.insert(0, "app/src/main/python")

from androidtv.compat import AndroidExecutor, android_account  # noqa: E402
from androidtv.tv import TV_COMMAND_SPECS, run_tv  # noqa: E402


class FakeResult:
    def __init__(self, ok: bool, message: str) -> None:
        self._ok = ok
        self._message = message

    def getOk(self):  # noqa: N802 (matches the Kotlin bridge name)
        return self._ok

    def getMessage(self):  # noqa: N802 (matches the Kotlin bridge name)
        return self._message


class FakeTv:
    def __init__(self) -> None:
        self.launched: list[str] = []

    def launch(self, target: str):
        self.launched.append(target)
        return FakeResult(True, f"launched {target}")

    def deviceIp(self):  # noqa: N802 (matches the Kotlin bridge name)
        return FakeResult(True, "10.0.0.9")

    def deviceModel(self):  # noqa: N802 (matches the Kotlin bridge name)
        return FakeResult(True, "Fake TV / android 12 (sdk 31)")

    def uptimeSeconds(self):  # noqa: N802 (matches the Kotlin bridge name)
        return FakeResult(True, "12345")


def test_specs_shape() -> None:
    assert set(TV_COMMAND_SPECS) == {"tv.launch", "tv.cast"}
    for spec in TV_COMMAND_SPECS.values():
        assert spec["description"]
        assert isinstance(spec["required"], dict)
        assert isinstance(spec["optional"], dict)


def test_launch_dispatch() -> None:
    tv = FakeTv()
    reply = run_tv(tv, "tv.launch", {"target": "com.example.tv"})
    assert reply["ok"] is True
    assert tv.launched == ["com.example.tv"]


def test_launch_requires_target() -> None:
    assert run_tv(FakeTv(), "tv.launch", {})["ok"] is False


def test_unknown_tv_command() -> None:
    assert run_tv(FakeTv(), "tv.nope", {})["ok"] is False


def test_no_bridge() -> None:
    reply = run_tv(None, "tv.launch", {"target": "x"})
    assert reply["ok"] is False


def test_cast_without_library_is_a_clean_error(monkeypatch) -> None:
    monkeypatch.delitem(sys.modules, "pychromecast", raising=False)
    monkeypatch.setitem(sys.modules, "pychromecast", None)
    reply = run_tv(FakeTv(), "tv.cast", {"action": "status"})
    assert reply["ok"] is False
    assert "PyChromecast" in reply["error"]


class _FakeMediaStatus:
    player_state = "PLAYING"
    title = "Demo"


class _FakeMediaController:
    status = _FakeMediaStatus()
    played: list[tuple] = []

    def play(self): pass

    def pause(self): pass

    def stop(self): pass

    def play_media(self, url, mime, **kw):
        self.played.append((url, mime, kw.get("stream_type")))


class _FakeCastStatus:
    volume_level = 0.5
    volume_muted = False
    display_name = "Fake TV"


class _FakeCast:
    def __init__(self) -> None:
        self.media_controller = _FakeMediaController()
        self.status = _FakeCastStatus()
        self.disconnected: list = []

    def wait(self, timeout=None): pass

    def set_volume(self, level): pass

    def quit_app(self):
        self.quit_calls = getattr(self, "quit_calls", 0) + 1

    def disconnect(self, timeout=None):
        self.disconnected.append(timeout)


class _FakePyChromecast:
    def __init__(self) -> None:
        self.seen: list[tuple] = []
        self.cast = _FakeCast()

    def get_chromecast_from_host(self, host_tuple):
        assert isinstance(host_tuple, tuple) and len(host_tuple) == 5
        assert host_tuple[0] == "10.0.0.9" and host_tuple[1] == 8009
        self.seen.append(host_tuple)
        return self.cast


def test_cast_status_with_fake_library(monkeypatch) -> None:
    fake = _FakePyChromecast()
    monkeypatch.setitem(sys.modules, "pychromecast", fake)
    reply = run_tv(FakeTv(), "tv.cast", {"action": "status"})
    assert reply["ok"] is True, reply
    assert reply["payload"]["title"] == "Demo"
    assert fake.seen and fake.cast.disconnected == [5]


def test_cast_volume_play_url_branches(monkeypatch) -> None:
    fake = _FakePyChromecast()
    monkeypatch.setitem(sys.modules, "pychromecast", fake)
    assert run_tv(FakeTv(), "tv.cast", {"action": "volume", "value": 0.7})["ok"] is True
    assert run_tv(FakeTv(), "tv.cast", {"action": "volume"})["ok"] is False
    over = run_tv(FakeTv(), "tv.cast", {"action": "volume", "value": 1.5})
    assert over["ok"] is True and over["payload"]["volume"] == 1.0
    under = run_tv(FakeTv(), "tv.cast", {"action": "volume", "value": -2})
    assert under["ok"] is True and under["payload"]["volume"] == 0.0
    played = run_tv(FakeTv(), "tv.cast", {"action": "play_url", "url": "https://x/y.mp4"})
    assert played["ok"] is True
    assert ("https://x/y.mp4", "video/mp4", "BUFFERED") in fake.cast.media_controller.played
    assert run_tv(FakeTv(), "tv.cast", {"action": "play_url"})["ok"] is False
    assert run_tv(FakeTv(), "tv.cast", {"action": "pause"})["ok"] is True


def test_probe_demo_combines_launch_and_cast(monkeypatch) -> None:
    from androidtv.probe import probe_demo

    monkeypatch.setitem(sys.modules, "pychromecast", _FakePyChromecast())
    out = probe_demo(FakeTv())
    assert "launch={'ok': True" in out and "'player': 'PLAYING'" in out


def test_cast_play_url_reports_verified_state(monkeypatch) -> None:
    fake = _FakePyChromecast()
    monkeypatch.setitem(sys.modules, "pychromecast", fake)
    reply = run_tv(FakeTv(), "tv.cast", {"action": "play_url", "url": "https://x/y.mp4"})
    assert reply["ok"] is True, reply
    assert reply["payload"]["player"] == "PLAYING"
    assert reply["payload"]["title"] == "Demo"


def test_cast_play_url_dead_load_is_an_error(monkeypatch) -> None:
    class _DeadStatus:
        player_state = "UNKNOWN"
        title = None

    class _DeadMedia(_FakeMediaController):
        status = _DeadStatus()

    class _DeadCast(_FakeCast):
        def __init__(self) -> None:
            super().__init__()
            self.media_controller = _DeadMedia()

    class _DeadLib(_FakePyChromecast):
        def __init__(self) -> None:
            super().__init__()
            self.cast = _DeadCast()

    monkeypatch.setitem(sys.modules, "pychromecast", _DeadLib())
    monkeypatch.setattr("androidtv.tv._await_media_state",
                        lambda mc: {"player": "UNKNOWN", "title": None})
    reply = run_tv(FakeTv(), "tv.cast", {"action": "play_url", "url": "http://x/y.mp4"})
    assert reply["ok"] is False, reply
    assert "did not start playback" in reply["error"]


def test_await_media_state_polls_until_real() -> None:
    from androidtv.tv import _await_media_state

    class Flipping:
        def __init__(self) -> None:
            self.reads = 0

        @property
        def status(self):
            self.reads += 1
            if self.reads < 3:
                return type("S", (), {"player_state": "UNKNOWN", "title": None})()
            return type("S", (), {"player_state": "BUFFERING", "title": "x"})()

    state = _await_media_state(Flipping(), tries=4, gap_s=0)
    assert state == {"player": "BUFFERING", "title": "x"}


def test_await_media_state_gives_up_on_dead_load() -> None:
    from androidtv.tv import _await_media_state

    class Dead:
        status = type("S", (), {"player_state": "IDLE", "title": None})()

    assert _await_media_state(Dead(), tries=3, gap_s=0) == {"player": "IDLE", "title": None}


def test_cast_play_url_retries_boot_race(monkeypatch) -> None:
    """First LOAD races receiver boot (IDLE); the retry lands PLAYING."""
    import androidtv.tv as tv_mod

    states = [{"player_state": "IDLE", "title": None},
              {"player_state": "PLAYING", "title": "BBB"}]

    class RacingMedia(_FakeMediaController):
        def __init__(self) -> None:
            self.loads = 0

        def play_media(self, url, mime, **kw):
            self.loads += 1

        @property
        def status(self):
            s = states[min(self.loads - 1, 1)]
            return type("S", (), s)()

    class RacingCast(_FakeCast):
        def __init__(self) -> None:
            super().__init__()
            self.media_controller = RacingMedia()

    class RacingLib(_FakePyChromecast):
        def __init__(self) -> None:
            super().__init__()
            self.cast = RacingCast()

    fake = RacingLib()
    monkeypatch.setitem(sys.modules, "pychromecast", fake)
    monkeypatch.setattr(tv_mod, "_await_media_state",
                        lambda mc, **kw: {"player": mc.status.player_state,
                                          "title": mc.status.title})
    reply = run_tv(FakeTv(), "tv.cast", {"action": "play_url", "url": "https://x/b.mp4"})
    assert reply["ok"] is True, reply
    assert fake.cast.media_controller.loads == 2
    assert reply["payload"]["player"] == "PLAYING"


def test_cast_play_url_stream_param(monkeypatch) -> None:
    fake = _FakePyChromecast()
    monkeypatch.setitem(sys.modules, "pychromecast", fake)
    mc = fake.cast.media_controller
    assert run_tv(FakeTv(), "tv.cast",
                  {"action": "play_url", "url": "https://x/l.m3u8",
                   "stream": "live"})["ok"] is True
    assert ("https://x/l.m3u8", "video/mp4", "LIVE") in mc.played
    bad = run_tv(FakeTv(), "tv.cast",
                 {"action": "play_url", "url": "https://x/y.mp4", "stream": "vod"})
    assert bad["ok"] is False and "unknown stream type" in bad["error"]


def test_cast_play_url_quits_lingering_session_first(monkeypatch) -> None:
    fake = _FakePyChromecast()
    monkeypatch.setitem(sys.modules, "pychromecast", fake)
    reply = run_tv(FakeTv(), "tv.cast", {"action": "play_url", "url": "https://x/y.mp4"})
    assert reply["ok"] is True, reply
    assert fake.cast.quit_calls == 1

    class QuittingLoudly(_FakePyChromecast):
        def get_chromecast_from_host(self, host_tuple):
            cast = super().get_chromecast_from_host(host_tuple)

            def boom():
                raise OSError("nothing running")

            cast.quit_app = boom
            return cast

    monkeypatch.setitem(sys.modules, "pychromecast", QuittingLoudly())
    assert run_tv(FakeTv(), "tv.cast",
                  {"action": "play_url", "url": "https://x/y.mp4"})["ok"] is True


def test_android_health_adds_model_and_temp(monkeypatch) -> None:
    import androidtv.compat as compat_mod

    monkeypatch.setattr(compat_mod, "_android_temperature", lambda: 36.6)
    ex = AndroidExecutor(android_account("/tmp"), FakeTv())
    reply = ex.run("device.health", {})
    assert reply["ok"] is True, reply
    payload = reply["payload"]
    assert payload["model"] == "Fake TV / android 12 (sdk 31)"
    assert payload["temperature_c"] == 36.6
    assert payload["uptime_s"] == 12345  # Mac has no /proc/uptime either
    assert "version" in payload


def test_android_health_keeps_upstream_temp_and_survives_no_bridge(monkeypatch) -> None:
    import androidtv.compat as compat_mod
    import musegadget.executor as upstream

    monkeypatch.setattr(compat_mod, "_android_temperature", lambda: 36.6)
    monkeypatch.setattr(upstream, "device_health",
                        lambda: {"version": "x", "temperature_c": 50.0})
    ex = AndroidExecutor(android_account("/tmp"), None)
    payload = ex.run("device.health", {})["payload"]
    assert payload["temperature_c"] == 50.0
    assert "model" not in payload


def test_proc_health_fallback_reads_proc(monkeypatch) -> None:
    import builtins
    import io

    from androidtv.compat import _proc_health_fallback

    canned = {
        "/proc/uptime": "123.4 56.7\n",
        "/proc/loadavg": "0.11 0.22 0.33 1/2 3\n",
        "/proc/meminfo": "MemTotal:        8000000 kB\nMemAvailable:    4000000 kB\n",
    }
    real_open = builtins.open

    def fake_open(path, *args, **kwargs):
        if str(path) in canned:
            return io.StringIO(canned[str(path)])
        return real_open(path, *args, **kwargs)

    monkeypatch.setattr(builtins, "open", fake_open)
    health = _proc_health_fallback()
    assert health["uptime_s"] == 123
    assert health["load"] == [0.11, 0.22, 0.33]
    assert health["memory_mb"] == {"total": 7812, "available": 3906}
    assert "disk_gb" in health  # real disk, works everywhere


def test_android_health_survives_upstream_crash(monkeypatch) -> None:
    import musegadget.executor as upstream

    def boom():
        raise AttributeError("module 'os' has no attribute 'getloadavg'")

    monkeypatch.setattr(upstream, "device_health", boom)
    ex = AndroidExecutor(android_account("/tmp"), FakeTv())
    payload = ex.run("device.health", {})["payload"]
    assert payload["model"].startswith("Fake TV")
    assert "version" in payload


def test_check_upstream_commands_guard() -> None:
    from androidtv.compat import check_upstream_commands

    check_upstream_commands()  # today's upstream passes
    with pytest.raises(RuntimeError, match="dropped commands"):
        check_upstream_commands({"file.read": {}, "file.write": {}})
    with pytest.raises(RuntimeError, match="file ops changed"):
        check_upstream_commands({"file.read": {}, "file.write": {},
                                 "file.append": {}, "device.health": {}})


def test_cast_unreachable_disconnects_cleanly(monkeypatch) -> None:
    class Exploding(_FakePyChromecast):
        def get_chromecast_from_host(self, host_tuple):
            raise OSError("no route")

    monkeypatch.setitem(sys.modules, "pychromecast", Exploding())
    reply = run_tv(FakeTv(), "tv.cast", {"action": "status"})
    assert reply["ok"] is False
    assert "unreachable" in reply["error"]


def test_file_fence_blocks_credential_store(monkeypatch, tmp_path) -> None:
    state = tmp_path / "state"
    state.mkdir()
    (state / "pairing.json").write_text("{}")
    monkeypatch.setenv("MUSEGADGET_STATE_DIR", str(state))
    ex = AndroidExecutor(android_account(str(tmp_path)))
    reply = ex.file_op("read", {"path": str(state / "pairing.json")})
    assert reply["ok"] is False
    assert "outside" in reply["error"]


def test_reset_pairing_keeps_identity_and_token(monkeypatch, tmp_path) -> None:
    from androidtv.pairing import reset_pairing

    files = tmp_path / "files"
    state = files / "musegadget"
    state.mkdir(parents=True)
    (state / "identity.json").write_text('{"node_id": "x"}')
    (state / "pairing.json").write_text('{"access_token": "y"}')
    (state / "sdk_token").write_text("mgst_dummy")
    monkeypatch.setenv("MUSEGADGET_STATE_DIR", str(state))
    reset_pairing(str(files))
    assert sorted(p.name for p in state.iterdir()) == ["identity.json", "sdk_token"]


def test_file_roundtrip_in_process(tmp_path) -> None:
    import base64

    target = tmp_path / "hello.txt"
    body = b"hello tv"
    ex = AndroidExecutor(android_account(str(tmp_path)))
    wrote = ex.file_op("write", {
        "path": str(target),
        "data_b64": base64.b64encode(body).decode(),
        "offset": 0,
        "final": True,
    })
    assert wrote["ok"] is True, wrote
    read = ex.file_op("read", {"path": str(target)})
    assert read["ok"] is True
    assert base64.b64decode(read["payload"]["data_b64"]) == body


def test_system_run_without_android_shell_is_a_clean_error(tmp_path) -> None:
    # /system/bin/sh exists only on device; anywhere else this must fail
    # loudly, never hang or traceback.
    ex = AndroidExecutor(android_account(str(tmp_path)))
    reply = ex.system_run({"command": "echo hi", "timeout_ms": 5000})
    import os

    if os.path.exists("/system/bin/sh"):
        assert reply["ok"] is True
    else:
        assert reply["ok"] is False
        assert "could not start" in reply["error"]
