# Changelog

## Unreleased

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
