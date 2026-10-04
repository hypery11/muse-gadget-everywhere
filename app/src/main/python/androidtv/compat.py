"""Android compatibility shims.

Upstream musegadget assumes Debian: /bin/bash, POSIX user accounts, a second
Python to spawn for file ops. None of that exists in an app sandbox, so this
module adapts at our layer. Upstream stays an untouched submodule.
"""

from __future__ import annotations

import os

from musegadget.executor import Executor as UpstreamExecutor

ANDROID_PATH = "/system/bin:/system/xbin"
ANDROID_SHELL = "/system/bin/sh"

# Upstream commands whose behaviour the overlay overrides. Guarded below:
# if upstream renames one or adds a file op, fail loudly at import so the
# overlay adapts the same day (docs/UPSTREAM.md) instead of drifting.
_OVERRIDDEN_COMMANDS = frozenset({"file.read", "file.write", "device.health"})


def check_upstream_commands(specs=None) -> None:
    from musegadget.executor import COMMAND_SPECS

    specs = COMMAND_SPECS if specs is None else specs
    missing = _OVERRIDDEN_COMMANDS - set(specs)
    if missing:
        raise RuntimeError(
            f"upstream dropped commands we override: {sorted(missing)}; "
            "see docs/UPSTREAM.md"
        )
    file_ops = {name for name in specs if name.startswith("file.")}
    if file_ops != {"file.read", "file.write"}:
        raise RuntimeError(
            f"upstream file ops changed: {sorted(file_ops)}; "
            "mirror them in compat.file_op"
        )


check_upstream_commands()


def _proc_health_fallback() -> dict:
    """device_health rebuilt from /proc files alone (no os.getloadavg).

    Every read is individually guarded: a missing node skips its key,
    never the whole report.
    """
    import shutil
    import socket

    import musegadget

    health: dict = {"version": musegadget.__version__}
    try:
        health["hostname"] = socket.gethostname()
    except Exception:
        pass
    try:
        with open("/proc/uptime", encoding="utf-8") as fh:
            health["uptime_s"] = int(float(fh.read().split()[0]))
    except Exception:
        pass
    try:
        with open("/proc/loadavg", encoding="utf-8") as fh:
            health["load"] = [round(float(x), 2) for x in fh.read().split()[:3]]
    except Exception:
        pass
    try:
        meminfo = {}
        with open("/proc/meminfo", encoding="utf-8") as fh:
            for line in fh:
                key, _, value = line.partition(":")
                meminfo[key] = value
        health["memory_mb"] = {
            "total": int(meminfo["MemTotal"].split()[0]) // 1024,
            "available": int(meminfo["MemAvailable"].split()[0]) // 1024,
        }
    except Exception:
        pass
    try:
        disk = shutil.disk_usage("/")
        health["disk_gb"] = {"total": round(disk.total / 1e9, 1),
                             "free": round(disk.free / 1e9, 1)}
    except Exception:
        pass
    return health


def _android_temperature() -> float | None:
    """First readable thermal zone, in Celsius. None when all are denied."""
    import glob

    for node in sorted(glob.glob("/sys/class/thermal/thermal_zone*/temp")):
        try:
            with open(node, encoding="utf-8") as fh:
                return int(fh.read().strip()) / 1000
        except (OSError, ValueError):
            continue
    return None


def android_account(home: str):
    """The app's own identity. No pwd lookup, no demotion: everything already
    runs as the app UID, which is exactly the sandbox we want."""
    from musegadget.executor import Account

    return Account(name="app", uid=os.getuid(), gid=os.getgid(), home=home)


class AndroidExecutor(UpstreamExecutor):
    """Executor that runs on Android: toybox sh, in-process file ops, tv.*."""

    def __init__(self, account, tv_control=None) -> None:
        super().__init__(account)
        self._tv_control = tv_control
        from androidtv.workspace import Workspace
        self.workspace = Workspace(os.path.join(account.home, "workspace"))
        self.developer_enabled = lambda: False

    def run(self, command: str, params: dict, timeout_ms: int | None = None) -> dict:
        if command.startswith("tv."):
            from androidtv import tv as tv_commands

            return tv_commands.run_tv(self._tv_control, command, params or {})
        if command == "device.health":
            from musegadget.executor import ok

            return ok(self.android_health())
        return super().run(command, params, timeout_ms)

    def android_health(self) -> dict:
        """Upstream health plus Android model and best-effort temperature.

        Upstream `device_health` already covers uptime/load/memory/disk via
        /proc (all readable in the sandbox); only `model`
        (/proc/device-tree, absent on Android) and `temperature_c`
        (thermal_zone0, usually SELinux-denied) need filling in. Gaps stay
        absent rather than faked, matching upstream's graceful pattern.
        """
        from musegadget.executor import device_health

        try:
            health = device_health()
        except Exception:
            # Upstream guards with `except OSError`, but Chaquopy's Android
            # build lacks os.getloadavg entirely (AttributeError). Fall back
            # to /proc reads that never touch the missing syscall.
            health = _proc_health_fallback()
        if self._tv_control is not None:
            from androidtv.tv import _bridge_message, _bridge_ok

            model = self._tv_control.deviceModel()
            if _bridge_ok(model):
                health["model"] = _bridge_message(model)
            if "uptime_s" not in health:
                uptime = self._tv_control.uptimeSeconds()
                if _bridge_ok(uptime):
                    try:
                        health["uptime_s"] = int(_bridge_message(uptime))
                    except ValueError:
                        pass
        if "temperature_c" not in health:
            temp = _android_temperature()
            if temp is not None:
                health["temperature_c"] = temp
        return health

    def _child_options(self) -> dict:
        options = super()._child_options()
        env = dict(options["env"])
        env["PATH"] = ANDROID_PATH
        options["env"] = env
        return options

    def system_run(self, params: dict, timeout_ms: int | None = None) -> dict:
        from musegadget.executor import error
        if not self.developer_enabled():
            return error("system.run is disabled; enable trusted developer mode on the device")
        # Mirror of upstream Executor.system_run with /system/bin/sh. Keep in
        # sync per docs/UPSTREAM.md whenever the submodule moves.
        import signal
        import subprocess
        import time

        from musegadget.executor import (
            DEFAULT_TIMEOUT_S,
            KILL_GRACE_S,
            MAX_TIMEOUT_S,
            _clip,
            error,
            log,
            ok,
        )

        command = params.get("command")
        if not isinstance(command, str) or not command.strip():
            return error("command is required")
        import math
        requested = params.get("timeout_ms", timeout_ms)
        if requested is not None and (isinstance(requested, bool) or not isinstance(requested, (int, float)) or not math.isfinite(requested) or requested <= 0):
            return error("timeout_ms must be positive and finite")
        timeout_s = min(requested / 1000 if requested is not None else DEFAULT_TIMEOUT_S, MAX_TIMEOUT_S)
        cwd = params.get("cwd") or self.account.home
        log.info("system.run as %s (timeout %ss)", self.account.name, timeout_s)
        started = time.monotonic()
        try:
            proc = subprocess.Popen(
                [ANDROID_SHELL, "-c", command], cwd=cwd,
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                **self._child_options(),
            )
        except OSError as exc:
            return error(f"could not start command: {exc}")
        # Drain both pipes incrementally: communicate() buffers unlimited output
        # before clipping, allowing an otherwise bounded job to exhaust the app.
        import selectors
        buffers = {proc.stdout: bytearray(), proc.stderr: bytearray()}
        truncated, timed_out = False, False
        limit = 32768
        deadline = started + timeout_s
        with selectors.DefaultSelector() as selector:
            for pipe in buffers:
                os.set_blocking(pipe.fileno(), False)
                selector.register(pipe, selectors.EVENT_READ)
            while selector.get_map():
                if time.monotonic() >= deadline:
                    timed_out = True
                    break
                for key, _ in selector.select(min(0.1, max(0, deadline-time.monotonic()))):
                    try:
                        chunk = os.read(key.fileobj.fileno(), 65536)
                    except BlockingIOError:
                        continue
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    buffer = buffers[key.fileobj]
                    remaining = limit - len(buffer)
                    buffer.extend(chunk[:remaining])
                    truncated |= len(chunk) > remaining
            if timed_out:
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
        for pipe in buffers:
            pipe.close()
        try:
            proc.wait(timeout=max(0.01, deadline-time.monotonic()) if not timed_out else KILL_GRACE_S)
        except subprocess.TimeoutExpired:
            timed_out = True
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            proc.wait(timeout=KILL_GRACE_S)
        out, out_cut = _clip(bytes(buffers[proc.stdout]))
        err, err_cut = _clip(bytes(buffers[proc.stderr]))
        return ok({
            "stdout": out,
            "stderr": err,
            "exit_code": proc.returncode,
            "timed_out": timed_out,
            "truncated": truncated or out_cut or err_cut,
            "duration_ms": int((time.monotonic() - started) * 1000),
        })

    def file_op(self, op: str, params: dict) -> dict:
        from musegadget.executor import error, ok
        try:
            if op == "read":
                return ok(self.workspace.read(params))
            if op == "write":
                return ok(self.workspace.write(params))
            return error(f"unsupported file op: {op}")
        except Exception as exc:
            return error(f"{type(exc).__name__}: {exc}")
