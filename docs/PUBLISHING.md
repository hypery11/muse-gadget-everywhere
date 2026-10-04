# What belongs in the public repository

Publish the app source, tests with synthetic fixtures, build and install instructions, API/architecture documentation, licensing, security boundaries, compatibility summaries and reviewed product artwork. Contributors should be able to build and understand the same app they download.

Keep personal account sessions, pairing state, SDK tokens, cookies, signing keys, device/network identifiers, raw logcat output, private conversations and operational notes outside this checkout. Internal launch plans, outreach drafts, growth snapshots and unfinished campaign sources also stay outside the public tree. Adding a file to `.gitignore` does not remove an already committed version or its history.

Before a pull request:

1. Run `python3 scripts/check_public.py` and `python3 scripts/check_docs.py`.
2. Inspect the staged diff, including screenshots, animations and binary assets. Secret scanners cannot establish that an image is free of personal information.
3. Record reviewed images in [the asset manifest](img/assets.json), including their source and checksum. A checksum records the reviewed version; it does not replace visual review.
4. Keep issue reports minimal. Replace names, addresses and tokens with neutral examples. Include only the log lines needed to reproduce a problem.

Build artifacts should contain the APK and checksum only. Account data and release-signing keys do not belong in APK assets or CI uploads. A debug signing key is a development credential, not a production identity.

If a real credential is exposed, revoke or rotate it first and follow [GitHub’s removal procedure](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository). Deleting the latest file does not remove existing clones, old commits or cached copies.
