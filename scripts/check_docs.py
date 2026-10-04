#!/usr/bin/env python3
"""Check tracked project Markdown links and referenced media (offline, no vendor scan)."""
import pathlib
import re
import sys
from urllib.parse import unquote, urlsplit

root = pathlib.Path(__file__).resolve().parents[1]
files = list(root.glob("*.md")) + list((root / "docs").rglob("*.md")) + list((root / ".github").rglob("*.md"))
errors = []
checked = 0
for file in files:
    text = file.read_text()
    text = re.sub(r"```.*?```", "", text, flags=re.S)
    links = re.findall(r"!?\[[^\]]*\]\(([^\s)]+)(?:\s+[^)]*)?\)", text)
    links += re.findall(r'(?:src|href)="([^"]+)"', text)
    for link in links:
        parts = urlsplit(link.strip("<>"))
        if parts.scheme or parts.netloc or not parts.path:
            continue
        target = (file.parent / unquote(parts.path)).resolve()
        checked += 1
        if not target.exists():
            errors.append(f"{file.relative_to(root)}: missing {link}")
if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print(f"Checked {checked} local links/media references in {len(files)} Markdown files")
