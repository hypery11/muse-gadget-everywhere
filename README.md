# Muse Gadget TV

One APK that turns a retired **Chromecast with Google TV** (or any Android
TV — or any Android phone/tablet, API 24+) into a complete Muse gadget.
No root, no Termux.

Upstream `musegadget` runs unmodified inside the app via
[Chaquopy](https://chaquo.com/chaquopy/); this repo is a thin Android shell:
BLE transport, foreground service, and `tv.*` commands (app launch on
any device; Cast-to-self wherever a Cast receiver runs).

Compatibility matrix (what was proven, on which API/ABI, and how):
[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## Status

- [x] Probe: BLE advertising support plus Chaquopy import check, on one TV
      screen. Verified on Chromecast with Google TV 4K (sabrina, Android 12).
- [x] Service: foreground service (`:gadget` process) hosting the musegadget
      run loop; boot restart once paired. Smoke-tested unpaired on device.
- [x] Pairing: native BLE setup window for the Muse phone app (same UUIDs
      and pairing v5 as upstream). Verified with a real iPhone pairing
      (encrypted handshake through `provision_v2`, `pairing.json` saved).
- [x] Commands: `tv.launch` verified (opens YouTube on screen);
      `tv.cast` verified end to end (`play_url` reaches PLAYING on the
      dongle itself; dead loads report errors, never fake "playing").
      PyChromecast ships in the APK.
- [x] Submission assets: photo set + Discord draft (`docs/DISCORD_POST.md`)
      ready. Still yours: post it; optionally film the TV for video.
- [x] Universal APK (v0.2.0): minSdk 24, all four ABIs, phone + Leanback
      launchers. Proven on API 24/31/34/35 and armv7a/arm64 (full
      phone-pairing proven on sabrina only; emulators prove install,
      probe, service, and advertise-start); x86/x86_64 ship in the APK
      (static-verified, no Intel host to boot).

## Screenshots

Main screen (probe green), YouTube opened by a Muse command, and the
`device.health` / closed-loop `echo` replies that prove the link:

![main screen](docs/img/01-main-probe.png)
![YouTube opened by tv.launch](docs/img/02-youtube-open.png)
![device.health reply](docs/img/03-chat-health.png)
![echo closed loop](docs/img/04-chat-echo.png)

## Build

Prerequisites: JDK 17, Android SDK (platform 35, build-tools 35),
`python3.11` on PATH (Chaquopy build requirement; 3.11 is the newest
Python with 32-bit Chaquopy support — and the newest that still covers
all four ABIs).

Verify with: `python3.11 --version && java -version && adb --version`.

```sh
git submodule update --init
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Upstream SDK lives in `vendor/muse-gadget-sdk` as a git submodule and is
compiled straight into the APK — no fork, no copy:

```
app/src/main/python/androidtv/   <- our overlay (probe, service glue, tv.*)
vendor/muse-gadget-sdk/linux/src <- upstream musegadget, unmodified
```

## TV setup (first time)

1. Chromecast: Settings > System > About, click the build 7 times.
2. Developer options > Wireless debugging > on > Pair with code.
3. On your Mac: `adb pair <ip>:<pair-port>` (enter the TV code), then
   `adb connect <ip>:5555` and accept the prompt on the TV.
4. `./gradlew :app:assembleDebug` and
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
5. Grant the overlay exemption so the background service may open apps:
   `adb shell appops set ai.muse.gadgettv SYSTEM_ALERT_WINDOW allow`.
   Without it `tv.launch` fails with an actionable error instead of a
   silent no-op (Android 10+ blocks background activity starts). The
   gate is deliberately uniform: even the foreground Demo button
   requires it, so the self-test exercises the same path the service
   uses at night with no foreground activity to hide behind.

## Pairing

1. Get an SDK token at
   [gadgets.muse.ai](https://gadgets.muse.ai/settings/sdk-tokens) and read
   the [Gadget SDK Terms](https://gadgets.muse.ai/sdk-terms).
2. Push it to the import dir (no permission needed):
   `printf '%s' 'mgst_…' | adb shell \
'cat > /sdcard/Android/data/ai.muse.gadgettv/files/import/muse_token.txt'`
   (use `printf`, not `echo -n`: some shells write a literal `-n`).
3. Open Muse Gadget TV > Pair > Open setup (10-minute window).
4. Phone: Muse app, Settings > Devices, turn on Developer mode, Add Device.
5. The TV shows the BLE name (`MuseGadgetXXXXXX`) and the actual on-air
   Bluetooth name (apps can't rename it — that needs a privileged API).
   The Muse app found and paired us under the Chromecast's own name, so
   no rename was needed; if your app filters by name, rename the
   Chromecast to the BLE name in Settings > System > About > Device name.

## Unpair / reset

- On the TV: main screen > Reset pairing (stops the service, deletes
  `pairing.json` only — identity and SDK token survive, like upstream
  `unpair`, so the BLE name stays stable across re-pairs).
- Or: `adb shell pm clear ai.muse.gadgettv` (also clears the token import).

## Troubleshooting

- `No SDK token found`: redo step 2 above; the Pair screen validates the
  shape and tells you when the file is malformed.
- `Bluetooth permission denied`: allow nearby-devices access, reopen Pair.
- Phone can't find the device: keep it within a few meters; if the scan
  filters by name, rename the Chromecast (see Pairing step 5).
- `Bluetooth is off`: enable Bluetooth in Chromecast settings first.
- Window closed without pairing: reopen it; check
  `adb logcat | grep -i bletransport` for `queued … pre-attach write`,
  `notify timeout`, or `setup peer bound` lines.
- Service won't stay up: `adb logcat -b all | grep python.stderr` shows
  the Python log; `run-as ai.muse.gadgettv ls files/musegadget/` shows
  identity/pairing/token state (debug builds only).
- `tv.launch` says overlay permission missing: run the appops command in
  TV setup step 5.
- Main screen buttons: Start service, Pair, Reset pairing, and Demo tv.*
  (opens YouTube, then reads our own Cast status — the on-device
  self-test for both tv commands; requires the overlay grant above).
- `tv.cast` actions: `status`, `play`, `pause`, `stop`, `volume`
  (`value` 0.0-1.0), `play_url` (`url` + optional `mime`/`stream`
  `BUFFERED` VOD default or `LIVE`); optional `host` overrides the
  default (this dongle). `play_url` quits any lingering receiver
  session first (a LOAD into one plays invisibly), retries once if the
  first LOAD raced receiver boot, then verifies a real player state —
  a dead load returns an error, never a fake "playing".
- `tv.cast` stuck at IDLE on every URL: the on-board Cast receiver can
  wedge after many launch/stop cycles; reboot the Chromecast (`adb
  reboot`) and retry. Some hosts also refuse the receiver's fetch —
  `https://www.w3schools.com/html/mov_bbb.mp4` is a known-good probe.
- Video plays but screenshots/screen recordings show black: expected —
  the receiver renders through a hardware composer overlay that capture
  can't see. Film the physical TV for demo footage.

## How it works

- `ProbeActivity` (0.1): reports BLE peripheral-mode support and proves
  `musegadget` + `cryptography` import on-device.
- `GadgetService` (0.2): foreground service hosting the asyncio run loop;
  state in the app's files dir via `MUSEGADGET_STATE_DIR`.
- Android sandbox: commands run as the app uid, and `compat.file_op`
  refuses paths outside the state dir, so a prompt-injected absolute
  path can't reach credentials or system files.
- `BleTransport` (0.3): implements upstream's `Transport` protocol
  over `BluetoothGattServer`, reusing `SetupController` unchanged — same
  UUIDs, same pairing v5 crypto. Two Chaquopy lessons live in
  `androidtv/pairing.py`: `_TransportAdapter` (Chaquopy can't convert a
  Python list to `java.util.List`, so sends loop per-packet `send_packet`)
  and `_BytesBoundary` (RX `jarray` scalar reads are signed, so values
  are normalised to real `bytes` before upstream framing sees them).
- `tv.*` commands: `tv.launch` (intents), `tv.cast` (Cast loopback to self).

## License

Apache-2.0, matching upstream. Upstream third-party notices apply unchanged.
