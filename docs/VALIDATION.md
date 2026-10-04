# Reproducing validation

Use generated local fixtures for regression tests. Instrumentation disables the
upstream cloud run loop unless explicitly told otherwise, preserving real
account credentials. It exercises the packaged Python and native libraries,
local runtime, real player state, OCR/QR, screens and rapid service restarts.

## Host and build checks

From the repository root, with Python 3.11, JDK 17 and Android SDK installed:

```sh
python3.11 -m venv .venv
.venv/bin/pip install pytest==8.3.4 cryptography==42.0.8 websockets==17.2 paho-mqtt==2.1.0
PYTHONPATH=app/src/main/python:vendor/muse-gadget-sdk/linux/src .venv/bin/python -m pytest app/src/main/python/androidtv/tests vendor/muse-gadget-sdk/linux/tests -q
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python3 scripts/check_apk.py app/build/outputs/apk/debug/app-debug.apk
```

The tests include invalid requests, file traversal/symlink rejection, shell denial,
real process timeout/output limits, event durability/expiry/retry/deduplication,
scene boundaries, redirect refusal, and real loopback HTTP/WebSocket/MQTT protocol
exchanges. Those integration fixtures do not prove a specific physical appliance.

## Android regression tests

With one test device selected in `ANDROID_SERIAL`:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w ai.muse.gadgeteverywhere.test/androidx.test.runner.AndroidJUnitRunner
```

The test creates silent WAV, OCR and QR fixtures beneath the app's workspace. It
validates playback, pause, seek, queue advance, stop, rendering, scenes, unavailable
hardware errors and three rapid service stop/start cycles. BLE advertise/release
runs only with available hardware and already granted BLE permissions; an
unsupported path is an explicit JUnit assumption skip.

Optional instrument arguments:

| Argument | Meaning | Scope |
|---|---|---|
| `-e castHost RECEIVER_IP` | Discover receivers; play a short public W3Schools sample; verify pause/resume/current volume/stop; speak a short Chinese test phrase | Your selected real Cast test receiver |
| `-e cameraFixture true` | Capture the emulator's synthetic scene; grant CAMERA before the run and revoke it afterward | Emulator fixture only, not a real camera |
| `-e cloud true` | Enable actual account traffic and verify connected state | Separate supervised run, never unattended credential-sensitive regression |

For camera tests, run `adb shell pm grant ai.muse.gadgeteverywhere android.permission.CAMERA`
before instrumentation and `adb shell pm revoke ai.muse.gadgeteverywhere android.permission.CAMERA`
after it finishes. Revocation kills the app process, so it must not occur inside a test.

The `cloud=true` path runs a separate registration test and skips the restart stress
case. It waits for cooperative shutdown before returning.

A test process can terminate while OAuth refresh is in flight. Keep cloud tests
separate from stress loops; server and local token storage cannot be made atomic
against abrupt process death. Normal service stop is cooperative and SDK-token
reporting is cached across restarts.

## Packaging and soak checks

The modern runtime uses Python 3.13 and 64-bit ABIs. Check every ELF load segment,
including native extensions inside Chaquopy asset archives:

```sh
./gradlew -PmodernRuntime=true :app:assembleDebug
python3 scripts/check_apk.py app/build/outputs/apk/debug/app-debug.apk --require-16k
```

Also run `zipalign -c -P 16 4` from Android build-tools on that APK, then install on
an actual 16 KiB emulator/device and confirm `adb shell getconf PAGE_SIZE` is 16384.
A static alignment result alone is not runtime validation.

Start the gadget service normally before the read-only soak recorder:

```sh
python3 scripts/soak.py "$ANDROID_SERIAL" --minutes 30 --output /tmp/muse-soak.jsonl
```

The recorder checks process presence and PSS every 30 seconds. It does not drive
commands or prove cloud reachability. Inspect process-specific logcat and actual
command responses alongside it. Use `--minutes 4320` for a 72-hour observation;
do not describe a shorter run as a 72-hour pass.
