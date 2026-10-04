# Contributing

PRs welcome. Small ones merge fast; big ones get read slowly and
possibly rewritten. Open an issue first if you're unsure — cheaper
than a rejected PR.

## The three rules

1. **Don't touch `vendor/`.** It's a git submodule tracking upstream
   `muse-gadget-sdk`, compiled in unmodified. If upstream needs a fix,
   fix it upstream; this repo adapts in `app/src/main/python/androidtv/`
   and the Kotlin shell only.
2. **Keep `lintDebug` green and the overlay tests passing.** CI runs
   `./gradlew :app:lintDebug` plus the pytest suite. If you add a
   behavior, add a test next to it (see `androidtv/tests/`).
3. **If it behaves differently on different devices, prove it.**
   Update `docs/COMPATIBILITY.md` with the config you ran
   (model / API / ABI) and how you observed it. Screenshot or logcat
   excerpt, or it didn't happen.

## Style

Match the file you're editing. Comments explain *why*, not *what* —
the codebase already leans that way, keep it that way. Commit messages
are short imperative (`Fix X`, `Add Y`), one logical change each.

## What I won't merge

- Wrappers around upstream behavior that only rename things.
- New permissions without a paragraph justifying them (this app runs
  a network service on people's TVs; the permission list stays short
  on purpose).
- Anything that reports success without verifying it. This project has
  been burned by fake-ok before; honest errors only.
