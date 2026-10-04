# Android compatibility and evidence

Version 0.3.0 has two builds. Both require API 24 and target API 35. Hardware
features are optional; missing microphones, cameras or BLE peripheral support
produce explicit unavailable states. A successful install does not prove every
feature on that device.

| Build | Python | ABIs | Page size | Intended devices |
|---|---|---|---|---|
| Default / universal | 3.11 | armeabi-v7a, arm64-v8a, x86, x86_64 | 4 KiB | Older Chromecast, ordinary Android devices |
| `-PmodernRuntime=true` | 3.13 | arm64-v8a, x86_64 | 4 or 16 KiB | Modern 64-bit Android devices |

The default build contains two 64-bit cryptography extensions with 4 KiB ELF
alignment. It must not be described as 16 KiB compatible. The modern build's
146 native ELF files pass the 16 KiB load-segment check, and the APK passes
`zipalign -c -P 16 4`. Its runtime was also exercised on an actual 16 KiB emulator.

## 0.3.0 validation matrix

The dated [validation summary](VALIDATION-RESULTS.md) records the results
and remaining work. The test procedures are in [VALIDATION.md](VALIDATION.md).

| Environment | Runtime coverage | Hardware-specific coverage |
|---|---|---|
| Chromecast with Google TV / sabrina, API 31, armv7, 4 KiB | Packaged Python, native media, cards, OCR/QR, scenes, lifecycle | Real Cast discovery/play/pause/resume/volume/stop, Chinese TTS, BLE advertising/cleanup |
| API 24 phone emulator, arm64, 4 KiB | Minimum-API runtime, native media, cards, OCR/QR, scenes, lifecycle | No BLE peripheral hardware; pairing is not proven here |
| API 35 phone emulator, arm64, 4 KiB | Target-API runtime, native media, cards, OCR/QR, scenes, lifecycle | Camera capture uses a synthetic emulator scene |
| API 35 phone emulator, arm64, 16 KiB | Modern Python 3.13 runtime, native media, cards, OCR/QR, scenes, lifecycle | Camera capture uses a synthetic emulator scene |
| x86 / x86_64 | Packaged libraries inspected | No Intel runtime test on this Apple Silicon host |

Chromecast reports no camera or microphone. Its remote-control microphone does
not become an app microphone merely because the remote has a voice button.
Real phone microphone recognition, physical camera quality, Home Assistant
appliances, and a household MQTT broker have not been tested in this environment.
Loopback HTTP/WebSocket/MQTT integration tests cover their protocol behavior.

Cloud verification is separate from local regression tests. Do not infer a fresh
Muse pairing or successful cloud command from a passing local test. The historic
0.2 cloud tests included an API-24 emulator with copied account credentials; that
is not a supported setup procedure and is not counted as fresh 0.3 pairing.

## Device setup notes

- A visible app may open screens/apps. Background launches need user-granted
  overlay access. A dispatch result is not proof that another app became visible.
- OEM background restrictions can still stop a service. The foreground
  notification reports observed connection state; the Probe screen links to
  battery settings where available.
- Cast-to-self falls back from Wi-Fi addressing to network interface enumeration
  on Ethernet-only devices. Receiver aliases reject ambiguous matches.
- Save the SDK token on Pair. The private copy survives later pairing windows;
  the old external import file is optional and is deleted after successful import.
- No 72-hour soak result is claimed. Android 16/17 behavior and a target-SDK
  upgrade are not validated by the API-35 run. Newer emulator images do exist.
- Release APKs remain unsigned unless private release-signing variables are
  supplied. No store submission or new public release is implied by these tests.
