# Runtime boundaries and permissions

Normal remote commands are schema-checked, capped at 128 KiB of parameters and 220 KiB of serialized result. Files live in a workspace separate from account tokens, private settings and event databases. Only the local settings UI can enable shell access, home actions, MQTT publishing and event forwarding. Camera and microphone flows require a visible screen and runtime permission. Backup is disabled in the manifest.

The Android UID is the credential boundary. Enabling trusted developer mode intentionally gives `system.run` that UID's full access, including tokens; a separate `:gadget` process would not provide a different UID. The service now shares the app process for the native media/voice/display bridge and stops cooperatively instead of killing the UI process.

The upstream SDK owns authentication, encryption, token rotation and cloud protocol. Vendor files are unchanged. `androidtv.cloud.AndroidService` persists a digest recording successful SDK-token reporting, avoiding an unnecessary OAuth token rotation on every Android service restart. Normal age-based rotation and authentication rejection handling remain upstream. Reset waits for an in-flight refresh before deleting the binding. Abrupt OS/process termination during a rotating-token exchange can still require re-pairing; no client can atomically commit a server-issued token and local storage across process death.

Regression instrumentation disables the cloud run loop by default and uses local generated fixtures. It must not rotate real credentials as a side effect of a test runner exiting. Explicit cloud tests must be separate, with the account owner available to re-pair. Do not copy account tokens between test devices.

## Why each permission is present

- Internet and network/Wi-Fi state support the Muse link, media URLs, selected LAN integrations and network diagnostics. `CHANGE_WIFI_MULTICAST_STATE` permits a short multicast lock during Cast mDNS discovery. It also satisfies the legitimate connected-device foreground-service prerequisite.
- Bluetooth advertise/connect permissions are used only for the setup peripheral. GATT startup waits for service and advertising callbacks, binds writes/notifications/MTU to one setup peer, limits queued packets, rejects prepared writes and cleans partial startup. BLE cryptography and framing remain upstream.
- Foreground-service, connected-device and media-playback permissions describe the long-running Muse connection and actual media/speech playback. The notification reports observed connection state. Android's notification permission controls ordinary notification visibility.
- Camera and record-audio permissions are optional hardware capabilities, requested by the visible capture/listen flow. Neither runs silently in the background. Camera, autofocus and microphone features are declared optional so TVs remain installable.
- Overlay access is a user-granted exemption allowing remotely requested screens/app launches when the app is in the background. It is not used to read another app's screen or inject input. Foreground use does not require it.
- Boot-completed permits restarting an already paired connection. Native media playback is not automatically started by the boot receiver.

Cleartext HTTP is enabled for explicitly chosen LAN media/Home Assistant endpoints. This is a functional tradeoff, not certificate bypass: HTTPS continues to validate certificates, media redirects cannot downgrade HTTPS, and HA redirects are refused. Prefer HTTPS and MQTT TLS on networks you do not control.

No accessibility service, notification-listener access, screen recording, root, broad storage permission, or arbitrary app-screen scraping is added. A default shell command returns an error; it cannot be enabled remotely through this command interface.

## Dependency audit, 2026-10-04

The audit is **not clean**. `pip-audit` reports 11 entries representing seven
unique advisories for `cryptography==42.0.8`. The Android wheel index currently
provides 42.0.8 for the supported Python/ABI combinations; merely changing the
version pin to a current PyPI release does not produce an Android native wheel.
See the [Chaquopy package index](https://chaquo.com/pypi-13.1/cryptography/).

| Advisory | Current assessment |
|---|---|
| [GHSA-r6ph-v2qm-q3c2](https://github.com/pyca/cryptography/security/advisories/GHSA-r6ph-v2qm-q3c2) | Affects SECT curves. The unchanged SDK uses SECP256R1 for BLE and X25519 for Noise. No affected curve is selected in these paths. |
| [GHSA-m959-cc7f-wv43](https://github.com/pyca/cryptography/security/advisories/GHSA-m959-cc7f-wv43), [GHSA-jwv3-5hgf-82ww](https://github.com/pyca/cryptography/security/advisories/GHSA-jwv3-5hgf-82ww), [GHSA-m2h6-j472-rp4c](https://github.com/pyca/cryptography/security/advisories/GHSA-m2h6-j472-rp4c) | Concern PyCA's X.509 verifier. Application/SDK paths do not call that verifier; HTTPS uses Python `ssl` or Android TLS. This is reachability analysis, not a patched dependency. |
| [GHSA-79v4-65xg-pq4g](https://github.com/pyca/cryptography/security/advisories/GHSA-79v4-65xg-pq4g), [GHSA-h4gh-qq45-vh27](https://github.com/pyca/cryptography/security/advisories/GHSA-h4gh-qq45-vh27), [GHSA-537c-gmf6-5ccf](https://github.com/pyca/cryptography/security/advisories/GHSA-537c-gmf6-5ccf) | Describe OpenSSL bundled in PyCA's PyPI wheels. Chaquopy builds its own wheels, so the Python package version alone cannot resolve these findings. Actual packaged OpenSSL versions are reported by `device.capabilities.runtime`; native OpenSSL advisories require separate review. |

The tested modern runtime reports Python 3.13.9; the default runtime reports
Python 3.11.14. Both report OpenSSL 3.0.18 for `ssl` and cryptography. OpenSSL's
[3.0 advisory list](https://openssl-library.org/news/vulnerabilities-3.0/index.html)
includes later fixes: for example CVE-2026-34180 affects versions before 3.0.21.
No evidence of an Android backport of those fixes was obtained, so these native
dependencies remain unresolved. A maintained Android build of current cryptography/OpenSSL, followed
by all ABI, pairing, Noise and TLS regression tests, remains a release-hardening
requirement. Findings have not been globally ignored or relabeled as passes.

The old zeroconf 0.39.4 dependency was replaced with 0.149.16. Its pure-Python
wheel uses the upstream build option and contains no modified runtime source;
provenance, hash and reproduction instructions are in [app/wheels](../app/wheels/README.md).
The repeat audit reported no zeroconf findings. This audit covers pinned Python
requirements, not a complete Android/OS SBOM or every bundled native dependency.
