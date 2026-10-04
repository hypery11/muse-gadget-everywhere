# Using the Android device runtime

The same validated command interface serves Muse, **Device controls**, and local scenes. Run `device.capabilities` first: available hardware, permissions, speech engine readiness and command names are returned from this device.

## Display, media and speech

| Command | Parameters | Observed outcome |
|---|---|---|
| `screen.show` | Optional `title`, `text`, `image`, `buttons: [{id,label}]`, `ttl_s` | Requested job; `screen.status` confirms rendering and image completion |
| `screen.clear`, `screen.status` | None | Hidden/rendered state of this app's screen |
| `media.play` | `url`; optional `queue`, `subtitle`, `mime`, `title` | Native player job, then `media.status` shows playing/buffering/error |
| `media.control` | `action`: pause/resume/stop/next/previous/seek/volume; `value` for seek seconds or volume 0–1 | Observed player state, position and queue index |
| `media.cache` | HTTP(S) `url`, relative destination `path` | Atomic workspace download, up to 64 MiB per file |
| `speech.say` | `text`; optional BCP-47 `language`, `rate` 0.5–2 | Queued utterance; `speech.status` confirms speaking/completed/error |
| `speech.stop`, `speech.status` | None | Installed engine and utterance state |

Native playback works without a Cast receiver. HLS, DASH, normal media files, VTT/SRT subtitles, queues, seeking, audio focus and Android MediaSession are supported; DRM provisioning is not implemented. Codecs depend on the device. MediaSession controls this app's player, not arbitrary apps.

`screen.show`, `media.play`, `tv.launch`, camera and voice screens need a visible app or Android's overlay exemption. `tv.launch` reports **dispatched**: Android does not provide confirmation that another app rendered successfully. HTTP(S) URLs and installed package names are accepted; arbitrary `intent://` URIs are rejected.

A useful first command:

```json
{"command":"screen.show","params":{"title":"Muse is ready","text":"Native display on this Android device","buttons":[{"id":"continue","label":"Continue"}],"ttl_s":60}}
```

An offline scene, saved with `automation.put` and run with `automation.run`:

```json
{"id":"welcome","actions":[{"command":"screen.show","params":{"title":"Welcome","text":"Everything in this scene runs locally."}},{"command":"speech.say","params":{"text":"Welcome","language":"en-US"}}]}
```

## Cast receivers and installed apps

`apps.list` returns visible launchable package names. `tv.launch` accepts one as `target`. `cast.discover` performs a bounded LAN mDNS discovery; `cast.devices` retains the results across restarts. Set an alias and room with `cast.name` using the discovered `id`.

`tv.cast` accepts `action` (`status`, `play_url`, `play`, `pause`, `stop`, `volume`), an optional `host` or remembered `device` ID/name/room, and action parameters `url`, `mime`, `stream`, or `value`. Ambiguous names are rejected. With no target, Cast-to-self uses the Android device's IPv4 address; this requires an actual Cast receiver on that device.

Cast commands are serialized. Play/pause/stop and volume require receiver state confirmation. A buffering or paused `play_url` response says **accepted**, not playing. Cast protocol acknowledgements cannot prove audible sound or what a physical TV panel displays.

## Camera, OCR and push-to-talk

`camera.capture` opens a visible camera screen. The user grants permission and taps **Take photo**; the JPEG is stored beneath `workspace/camera/`. `vision.analyze` accepts a workspace image `path` and `mode` (`text`, `barcode`, `both`). OCR and barcode models are bundled and run on device. Images are not uploaded by these commands. OCR supports Chinese and Latin text; this is not a general image-captioning model.

`voice.listen` opens a visible push-to-talk screen. Recording begins only after the user taps **Listen** and grants microphone access. On-device recognition is offered only when Android reports it available. The other speech engine may send audio to its provider, as stated on screen. Review/edit the transcript before queuing it to Muse. Local phrases pause/resume/stop (English or Chinese) or `scene NAME` invoke the local player/scenes. No wake word or continuous background microphone is implemented.

Chromecast has no app microphone/camera in the tested configuration. A remote's Assistant microphone is not treated as an ordinary app microphone.

## Sensors, events and offline rules

`sensors.read` reports battery, charging, network readiness and any available ambient light, proximity, accelerometer or temperature samples. Sampling is brief, with sample age included; absent hardware produces absent values.

`events.emit` creates a local event; `events.list` shows recent events. Muse forwarding requires both a requesting event and **Forward events to Muse** in local settings. Built-in home, MQTT, battery/power, end-of-media and button events request forwarding by default only when that local setting is enabled. Camera events and speech completion remain local. Retained MQTT snapshots never trigger rules or cloud forwarding.

Events are persisted with stable IDs, expiry and delivery state. The store keeps 200 events, identical bursts are deduplicated for two seconds, retries wait 30 seconds, and cloud sends are limited to one per five seconds. A received network acknowledgement is not an assistant reply. Delivery is at least an attempt with bounded retries/expiry, **not exactly once**; use event IDs to recognize duplicates.

`automation.put` saves 1–10 allowed actions, plus either an `event` with exact-field `match` and `cooldown_s`, or `interval_s` (minimum 60). An omitted trigger creates a manually run scene. There are at most 100 scenes; actions run in order and stop on the first failure. Missed interval runs are not replayed after a restart. `automation.list`, `automation.run` and `automation.delete` manage them. Scheduled work requires the service to remain running; Android may suspend or kill apps.

## Home Assistant and MQTT

Enter credentials directly under **Device controls → Settings**. Saving settings stops the service; start it again to apply connections. Commands cannot edit these settings or read their secrets.

| Integration | Setup | Commands and controls |
|---|---|---|
| Home Assistant | Final base URL, token; optional event entity list | `home.status`, `home.states` with optional `entity_id`; `home.call` requires local action opt-in and explicit entity IDs |
| MQTT | Host, port, prefix, optional username/password, TLS | `mqtt.status`; subscribe to `prefix/#`; `mqtt.publish` requires local publish opt-in and a topic beneath that prefix |

Use HTTPS/TLS when available. Certificate validation stays enabled. Plain HTTP or unencrypted MQTT may be necessary on an explicitly configured private LAN and provides no transport confidentiality. Home Assistant HTTP and WebSocket redirects are refused to protect its token.

Home actions report accepted/changed states; physical completion depends on the integration. MQTT success means the broker acknowledged QoS 1, not that an appliance acted. No actual household automation is enabled by default.

## Workspace, diagnostics and trusted developer mode

`file.read`/`file.write` accept paths inside `files/workspace`, preferably relative. Chunks are at most 64 KiB, files at most 64 MiB; writes finalize atomically. Parent/file symlinks and hidden segments are rejected by the file API. Existing scripts using arbitrary absolute app paths must migrate.

`device.health` reads available system health. `device.status` shows the connection, event delivery states and recent command outcomes. Failed event delivery records a short HTTP status or exception class in `last_error`, without storing the server response body. Events target a stable UUID side-chat per device. The bounded command audit stores names, success flags and durations, not command parameters or output. Event bodies may contain transcript or configured home state, so treat them as user data.

`system.run` is absent from the normal command catalog. **Trusted developer mode** is a local opt-in with an explanation: a shell runs under the app UID and can read app credentials. It is not sandboxed away from those credentials. Its output and execution time are bounded. Keep the mode off for normal household use.
