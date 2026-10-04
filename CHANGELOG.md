# Changelog

## Unreleased — 0.3 development

- Add native message cards, media playback/queues, TTS, visible voice/camera flows,
  bundled OCR/barcodes, sensors, Cast discovery, local scenes, events and optional
  Home Assistant/MQTT integration. See `docs/FEATURES.md` for exact behavior.
- Replace the long controls list with Home, Display, Media, Scenes and Device
  destinations: TV side navigation, compact bottom navigation, persistent drafts,
  input validation, readable results and accessible focus states.
- Guide token setup and pairing, offer controls after successful pairing,
  confirm pairing/scene deletion, and group integration settings.
- Preserve the unchanged upstream SDK while fixing graceful service restart,
  BLE lifecycle, SDK-report deduplication and UUID event sessions.
- Restrict file operations to the workspace; make the trusted developer shell
  a local opt-in. Native cryptography advisories remain unresolved.
- Add a modern Python 3.13 build for 64-bit/16 KiB devices alongside the
  four-ABI Python 3.11 build required by 32-bit Chromecast userspace.
- Add real device/emulator verification, native alignment checks and CI artifacts.
- Refresh README visuals, add a Traditional Chinese guide, device/feature issue
  forms, a conduct policy and a practical community launch pack.

These source changes are not a published 0.3 release. Production signing,
physical phone/tablet and integration coverage, and a 72-hour soak remain open.

## 0.2.0 — one APK for every Android device

- minSdk 28 → 24, targetSdk 34 → 35; all four ABIs ship
  (armeabi-v7a, arm64-v8a, x86, x86_64). API-gated service paths
  (legacy notification + `startService` on 24/25, channels + typed
  foreground service on 26+), booted on phone emulators at 24/34/35
  and a TV emulator at 34 (25 shares 24's path, unbooted).
- All `<uses-feature>` now `required=false`: the APK installs
  anywhere and degrades honestly — Probe reports BLE peripheral
  readiness, overlay grant, and battery exemption; Pair refuses on
  peripheral-less hardware with guidance instead of an exception.
- In-app prerequisite setup: Probe buttons deep-link the overlay
  grant and the battery-exemption list (no more adb-only setup), and
  Probe requests POST_NOTIFICATIONS on 33+ so service-error alerts
  can actually show.
- `deviceIp` falls back from Wi-Fi to interface enumeration
  (Ethernet-only boxes); generic (non-Chromecast) copy throughout;
  all UI strings extracted to resources.
- Android 15 edge-to-edge insets, scrollable button row on narrow
  phones, no-shared-storage guard in the token import.
- `lintDebug` clean: 0 errors, 0 NewApi (newer-target warnings accepted in
  `lint-baseline.xml`; newer target behavior was not validated).
- Compatibility matrix with per-API/ABI proof: `docs/COMPATIBILITY.md`.

## 0.1.0 — Chromecast with Google TV

- Native BLE pairing on Android TV (same service UUIDs and pairing v5 as
  upstream; phone app pairs unchanged).
- Foreground service (`:gadget` process) hosting the musegadget run loop,
  with boot restart once paired.
- `tv.launch` (open apps/URLs via intents) and `tv.cast` (Cast control,
  defaulting to the dongle itself).
- `tv.cast play_url` hard-won reliability: quit lingering session first
  (invisible-playback fix), BUFFERED default with `stream` override
  (pychromecast's LIVE default never leaves IDLE on VOD), one retry
  when the first LOAD races receiver boot, and post-play verification
  so dead loads report errors instead of fake "playing".
- Probe screen: BLE capability report plus Chaquopy import check.
- Pairing reset button; SDK-token import validation; honest error states.
