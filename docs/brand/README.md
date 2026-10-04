# Public brand assets

Muse Gadget Everywhere is an open-source runtime for programmable Android devices. The campaign line is **Muse, beyond the chat.** Android phones, tablets and TV are different hosts; functionality depends on hardware and permissions.

The palette uses near-black `#101014`, blue `#6EB4FF` and white `#F2F3F5`. The app uses Android system typography. The geometric monoline mark is authored in the Android drawable resources.

`social-preview.html` is a fixed 1280 × 640 export composition. Serve the repository locally, open it in Chromium at that viewport, wait for `document.fonts.ready`, and capture to `docs/social-preview.png`. It uses the reviewed UI captures in `docs/img/`; their provenance and hashes are in [the asset manifest](../img/assets.json). Visible labels identify form factors only: Phone, Tablet and TV. The phone and tablet captures are emulator layouts; TV is a Chromecast capture.

`launcher-banner.html` is a 960 × 540 export source, resized for the four existing Android banner density resources. The sharing image must remain below GitHub’s 1 MB upload limit. Committing it does not change the repository’s Social preview setting.

Space Grotesk Bold is by Florian Karsten, from [the author’s repository](https://github.com/floriankarsten/space-grotesk/tree/master/fonts/woff2/static), with the included [SIL Open Font License](OFL.txt).
