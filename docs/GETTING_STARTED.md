# Get your first card on screen

[Project](../README.md) · [Build](BUILD.md) · [繁體中文](../README.zh-TW.md)

This guide describes **0.3 development source/CI builds**. The published 0.2 APK has the earlier Probe/Pair interface; it does not contain the new local control dashboard.

## Choose a build

| Device | Build |
| --- | --- |
| Chromecast with Google TV, including 32-bit userspace | Universal, Python 3.11 |
| Other Android 7.0+ devices with 4 KiB memory pages | Universal; check hardware capabilities after install |
| ARM64/x86_64 devices with 16 KiB pages | Modern, Python 3.13 |

If unsure, connect ADB and run `adb shell getconf PAGE_SIZE` (4096 or 16384) and `adb shell getprop ro.product.cpu.abi`. Universal and modern builds have the same package name; install one at a time. See [compatibility](COMPATIBILITY.md) and [build commands](BUILD.md).

## Install

On a phone/tablet, enable Developer options and USB debugging, connect USB and accept the Android authorization prompt. On Chromecast with Google TV, open Settings → System → About and tap the Android TV OS build entry seven times. Enable the debugging option offered by that device.

For network ADB, use the address/port shown by Android. If the device offers wireless pairing, run `adb pair HOST:PAIR_PORT`, then `adb connect HOST:CONNECT_PORT`. Older TV builds may offer debugging on port 5555 without a separate pairing-code flow. Ports and menu labels vary by Android version; do not assume the pairing port is the connection port.

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

An incompatible signing key blocks an in-place update. Do not uninstall reflexively: uninstalling erases pairing and local settings. Keep the original signing key for updates.

## Try local controls

1. Open Muse Gadget Everywhere and select **Start service**.
2. Select **Try a card**. The message field includes an editable example.
3. Select **Show card**. Confirm the text appears, then choose **Close**.
4. Optionally select **Speak text**. TTS needs an installed language/voice engine.

Local actions require the running service. A job being accepted does not prove playback, speech or rendering finished; use **Check display**, **Check speech** or **Check playback** to inspect progress.

## Connect Muse

1. Get your SDK token from [gadgets.muse.ai → Settings → SDK tokens](https://gadgets.muse.ai/settings/sdk-tokens). Read the [SDK terms](https://gadgets.muse.ai/sdk-terms).
2. Home → **Pair with Muse** → paste the token → **Save SDK token**. It stays in private app storage. Never include it in a screenshot or issue.
3. Select **Open setup (10 min)** and allow nearby-device access when asked. Bluetooth must be on, and the hardware must support BLE peripheral mode.
4. In the Muse phone app, open Settings → Devices, enable Developer mode if required, then Add Device.
5. Select this device. The app shows both its Muse BLE name and the actual on-air Bluetooth name. On the tested Chromecast, the normal Chromecast name worked. If your phone app filters by name, rename the Android device to the shown `MuseGadget…` name and retry.
6. When paired, select **Start service & open controls**. Wait for **Connected to your Muse**.
7. In a Muse chat, ask: “Show a welcome message on my TV.” Confirm the card on the physical screen.

For ADB-only setup, the legacy `muse_token.txt` import in the app’s external `files/import` directory is still supported. Open Pair once to create the directory. A successful import moves the token into private storage and removes the external file; keep credentials out of shell history.

## Optional Android permissions

**Device → Diagnostics & setup** links to overlay and battery settings. Overlay access permits background screen/app launches. Battery exemption can help with vendor task killers. These settings are not required just to try a visible local card. Some TVs do not expose a battery settings page; the app reports that limitation.

Camera and microphone permission are requested in their visible flows. Capture requires a tap; speech transcripts are reviewed before sending. Home Assistant, MQTT publishing, event forwarding and the trusted developer shell are configured separately in **Device → Settings**. Saving settings stops the service; return Home and start it again.

## Troubleshooting

| What you see | What to try |
| --- | --- |
| Service stopped / action asks to start service | Home → Start service; check the status line |
| No device in the pairing scan | Check Bluetooth, nearby-device permission, BLE peripheral support, proximity and the on-air name shown in Pair |
| Permission denied after declining the prompt | Tap Open setup to retry, or allow Nearby devices in Android app settings |
| Connected locally, Muse reconnecting | Check network access and saved pairing; do not reset pairing as the first troubleshooting step |
| No Cast receivers | Keep both devices on the same Wi-Fi; guest networks or multicast isolation can block discovery |
| Media URL fails | Use a direct playable media URL or workspace file, not a webpage URL; inspect Check playback |
| Background app launch blocked | Grant overlay access under Diagnostics & setup |
| Cast video is black in screenshots | Some receivers use a hardware overlay; film the physical TV for video evidence |
| Camera/voice unavailable | This hardware may not have a camera, microphone or speech recognizer. Check Device capabilities |

To intentionally remove the Muse binding: **Device → Diagnostics & setup → Reset pairing**, then confirm. The SDK token and device identity remain saved. Clearing app data erases more, so prefer the in-app reset when re-pairing is the intended action.

Still stuck? [Ask in Discussions](https://github.com/hypery11/muse-gadget-everywhere/discussions) or [report a bug](https://github.com/hypery11/muse-gadget-everywhere/issues/new?template=bug_report.yml). Include app version, device/Android version, exact steps and a redacted screenshot or log. See [SECURITY](../SECURITY.md) before sharing logs.
