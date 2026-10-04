# Contribute to Muse Gadget Everywhere

Thanks for helping another Android device find a useful second life. English and Traditional Chinese reports are welcome. A clear device report or a better setup step is as useful as code.

## Choose a starting point

| Contribution | Where to start |
| --- | --- |
| Try your TV, phone or tablet | [Device report](https://github.com/hypery11/muse-gadget-everywhere/issues/new?template=device_report.yml) — include what actually worked |
| Fix a confusing instruction | Edit the relevant guide under `docs/` or either README |
| Share a scene or demo | [Discussions](https://github.com/hypery11/muse-gadget-everywhere/discussions) — include device and app version |
| Propose a feature | [Feature request](https://github.com/hypery11/muse-gadget-everywhere/issues/new?template=feature_request.yml) — describe the task and current workaround |
| Change the app/runtime | Follow the setup and checks below |

For a large feature, open a discussion before investing in implementation. Small fixes can go straight to a pull request. Maintainers will review as time allows; there is no guaranteed response time.

## Set up development

1. Fork and clone with `git clone --recurse-submodules YOUR_FORK_URL`.
2. Follow [BUILD.md](docs/BUILD.md) for JDK 17, Android SDK and Python.
3. Create a branch and keep the change focused.
4. Run the relevant checks; include results and any gaps in the PR.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python3.11 -m pip install pytest cryptography==42.0.8 websockets==17.2 paho-mqtt==2.1.0
PYTHONPATH=app/src/main/python:vendor/muse-gadget-sdk/linux/src \
  python3.11 -m pytest app/src/main/python/androidtv/tests vendor/muse-gadget-sdk/linux/tests -q
python3 scripts/check_docs.py
```

For device tests and cloud isolation, follow [VALIDATION.md](docs/VALIDATION.md). Do not run lifecycle stress tests against a live account: the normal instrumentation suite isolates cloud traffic. Cloud verification is separate and opt-in.

Before sharing logs, screenshots or new files, follow the [public-content policy](docs/PUBLISHING.md). Run `python3 scripts/check_public.py` to catch private file classes and unreviewed documentation media. Review images visually and use synthetic test data; automated secret scanning cannot establish that a screenshot is free of private information.

## Project boundaries

- Keep `vendor/muse-gadget-sdk` unmodified. Adapt Android behavior in `app/src/main/python/androidtv/` and the Kotlin shell; propose upstream fixes upstream.
- Match existing style. Comments should explain why. Use short imperative commit messages and avoid unrelated formatting changes.
- Test changed behavior. For a UI change, include a real device/emulator screenshot and check both remote focus and touch where relevant.
- State the device, Android API, ABI and observation method when behavior depends on hardware. Add evidence to [COMPATIBILITY.md](docs/COMPATIBILITY.md); emulator results do not establish physical-device support.
- Explain any new Android permission and the user-visible action that needs it.
- Report actual outcomes. Accepted, completed and failed are different states; do not substitute one for another.
- Keep credentials, private chats, LAN details and personal files out of screenshots and logs. See [SECURITY.md](SECURITY.md).

## Good first contributions

These are starter ideas, not promised assignments. Check existing issues before starting:

- Improve one confusing step in the getting-started guide with a tested device-specific note.
- Submit a documented physical phone/tablet validation report.
- Add a regression test for an existing boundary case in workspace or scene validation.
- Translate a guide while retaining exact button labels and verified limitations.

Be considerate and follow the [Code of Conduct](CODE_OF_CONDUCT.md). By contributing, you agree that your contribution is licensed under this repository’s Apache-2.0 license.
