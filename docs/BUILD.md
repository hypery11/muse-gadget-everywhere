# Build Muse Gadget Everywhere

[Back to the project](../README.md) · [Validation](VALIDATION.md)

## Universal build

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
app/src/main/python/androidtv/   <- runtime, workspace, events, integrations, Android adapters
vendor/muse-gadget-sdk/linux/src <- upstream musegadget, unmodified
```

## Build variants and release signing

The default build keeps Python 3.11 and all four ABIs for older Chromecast
32-bit userspace. Its packaged crypto extension is **not 16 KiB compatible**.
For arm64/x86_64 devices needing 16 KiB support, install Python 3.13 and build:

```sh
./gradlew -PmodernRuntime=true :app:assembleDebug
python3 scripts/check_apk.py app/build/outputs/apk/debug/app-debug.apk --require-16k
```

`MUSE_BUILD_PYTHON` can point to the selected Python executable. Build variants
use the same output path: copy an APK before building another variant.
`-PsplitApks=true` also creates ABI APKs; Python asset archives limit how much
ordinary Android ABI splits can reduce size.

The **Gradle release build type** stays unsigned unless all four private environment variables are
provided: `MUSE_RELEASE_STORE`, `MUSE_RELEASE_STORE_PASSWORD`,
`MUSE_RELEASE_KEY_ALIAS`, `MUSE_RELEASE_KEY_PASSWORD`.
Run `./gradlew :app:assembleRelease` after configuring them in your secret store.
Never commit the keystore or passwords. An unsigned release build is useful for
packaging checks and is not an installable published release.

GitHub Release [0.3.0](https://github.com/hypery11/muse-gadget-everywhere/releases/tag/v0.3.0) distributes installable **debug build type** APKs, built with the same persistent development signing key used for the published 0.2 APK. Both universal and modern downloads include checksums. They are not production-signed; the signing key stays outside the repository. Ordinary CI artifacts below use separate, temporary keys and are not interchangeable updates.


For development checks, see [CONTRIBUTING](../CONTRIBUTING.md).

## Development CI artifacts

Successful GitHub Actions runs upload `muse-gadget-universal-debug` and `muse-gadget-modern-debug`, each containing an APK and `SHA256SUMS`. Download while signed into GitHub and unzip before installing. Retention is 14 days; a workflow run is not a published release.

CI uses an ephemeral debug signing key, which may differ from your local build or another run. Do not uninstall a paired installation just to try a different key: uninstalling erases its data. Use a spare test device or your own consistent signing key for repeatable updates. Production release signing remains separate.
