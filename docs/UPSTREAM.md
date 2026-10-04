# Upstream policy

Upstream (`facebookincubator/muse-gadget-sdk`) is a git submodule under
`vendor/` and is **never patched**. All Android adaptation lives in
`app/src/main/python/androidtv/` (compat shims, service/pairing entry
points, `tv.*`) and `app/src/main/java/` (BLE transport, service, UI).

## Updating the submodule

```sh
git submodule update --remote vendor/muse-gadget-sdk
```

Then verify, in order, before committing the bump:

1. `./gradlew :app:assembleDebug` (Chaquopy pip still resolves).
2. Overlay tests: `PYTHONPATH=vendor/muse-gadget-sdk/linux/src:app/src/main/python python3 -m pytest app/src/main/python/androidtv/tests/ -q`.
3. Run the isolated instrumentation suite and workspace roundtrip. Test actual
   cloud registration separately; do not let test-runner shutdown interrupt a
   production credential rotation. Pair with the phone app when needed.
4. Verify default `system.run` denial, native media/card status and Cast actions.
   Use trusted developer mode only when explicitly testing shell behavior.
5. Check `compat.py` against upstream `executor.py`: if upstream changed
   `system_run` timeout/kill semantics or added file ops, mirror them.

If upstream renames modules we import (`executor`, `service`, `pairing`,
`ble_setup`, `ble_framing`, `fileops`, `config`, `identity`, `network`,
`muse_api`), adapt the overlay the same day; never pin-and-forget.
