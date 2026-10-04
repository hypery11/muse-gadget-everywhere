# Validation summary — 0.3.0

Results were collected on 2026-10-04, with copy/artwork and release-package checks on 2026-10-05. This is a maintainer-reported summary. CI provides independently inspectable build results; private account sessions and raw device captures are not distributed. [Reproduce the checks](VALIDATION.md) · [Compatibility](COMPATIBILITY.md) · [Security limits](SECURITY.md).

| Check | Result and scope |
| --- | --- |
| Host Python regression | 197 passed; overlay and upstream SDK tests |
| Universal and modern builds | Debug, instrumentation APK and unsigned release built for both variants; ongoing results are in the [CI build history](https://github.com/hypery11/muse-gadget-everywhere/actions/workflows/ci.yml) |
| Android lint | 0 errors; 18 warnings and 2 existing baseline warnings |
| Modern native packaging | All 146 packaged native ELF libraries met 16 KiB alignment; zipalign passed |
| Minimum/target API fixtures | API 24 and 35 ARM64 emulator runtime checks, including a 16 KiB API 35 image |
| Current UI regression | Chromecast and API 35 phone emulator each passed 3 executed tests; 2 opt-in cloud/camera tests skipped in each run |
| Real Chromecast with Google TV, API 31 | BLE pairing, Muse commands, cards, TTS, Cast play/pause/resume/stop and reboot reconnection verified separately from cloud-isolated regression |
| Adaptive layout | TV D-pad navigation, phone layout, 1.3× font scaling and tablet-sized emulator layout inspected |
| Branding follow-up, 2026-10-05 | Universal debug build and lint passed after the device-neutral Home copy and launcher banner update; no new physical-device run |
| Published 0.3.0 packages, 2026-10-05 | Universal and modern rebuilt and linted; both signatures match published 0.2. Universal updated a clean API 35 fixture from the actual 0.2 download using `adb install -r`, preserving synthetic app data. Packaged runtime/UI regression passed on API 35 with 4 KiB (universal) and 16 KiB (modern): 3 executed, 2 opt-in tests skipped per build. Modern's 146 native libraries and ZIP packaging passed 16 KiB checks. |

The UI regression verifies navigation, draft retention and rejection of an invalid scene ID before dispatch. Runtime checks cover native media, OCR/barcode fixtures, local scenes and lifecycle. A passing local test does not prove cloud transport, hardware camera quality or a physical phone/tablet installation.

Release-package checks used fresh fixtures without Muse account credentials. The upgrade test establishes installer/data continuity, not a new cloud pairing or refresh-token migration test. Both installable APKs and `SHA256SUMS` are attached to [0.3.0](https://github.com/hypery11/muse-gadget-everywhere/releases/tag/v0.3.0); CI artifacts have different signing keys.

Still open: physical phone/tablet reports, real Home Assistant/MQTT installations, a 72-hour soak, production signing and the dependency advisories documented in [SECURITY.md](SECURITY.md). Tablet layouts are emulator fixtures. No blanket compatibility, security or long-term stability claim is made.
