# Security

## Don't paste secrets. Ever.

Bug reports often need logs, and logs sometimes contain credentials.
Before posting anything, scrub:

- SDK tokens (`mgst_…`)
- `pairing.json` / `identity.json` contents (post the *keys*, never the values)
- Your LAN IPs if you care (they're RFC1918, but still)

If you already posted one, revoke the token at
[gadgets.muse.ai](https://gadgets.muse.ai/settings/sdk-tokens),
re-pair, and say so in the thread so I know the leak is closed.

## Reporting a vulnerability

Don't open a public issue for it. Ping me in the Muse Discord
`#projects` thread for this project and I'll share an email. Include
what's affected and, if you have one, a minimal repro. I'll fix first,
credit you in the release notes (unless you'd rather stay anonymous).
