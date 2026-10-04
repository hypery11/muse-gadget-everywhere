# Brand assets

The app keeps its near-black stage (`#101014`), blue action color (`#6EB4FF`) and native Android typography. The authored monoline mark and navigation symbols are geometric vectors, stored in Android drawable resources. UI screenshots must come from Android, not from this artwork.

`social-preview.html` is a fixed **1280 × 640 export composition**, not a responsive landing page. Serve the repository locally, open this file in Chromium at that viewport, wait for `document.fonts.ready`, and export to `docs/social-preview.png`. Its inset is the unmodified real Chromecast capture from `docs/img/control-tv.png`.

The display typeface is **Space Grotesk Bold** by Florian Karsten, downloaded from [the author’s repository](https://github.com/floriankarsten/space-grotesk/tree/master/fonts/woff2/static). Its [SIL Open Font License](OFL.txt) is included. The app itself continues to use Android’s system typeface for readable native controls.

The sharing image must stay under GitHub’s 1 MB limit. Updating the PNG in Git does not update the repository’s Social preview setting; upload it there separately.
