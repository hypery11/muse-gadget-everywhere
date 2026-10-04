#!/usr/bin/env python3
"""Reject private file classes and unreviewed documentation media; never print values."""
import hashlib
import json
import pathlib
import re
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
names = subprocess.check_output(
    ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=root
).decode().split('\0')
files = [pathlib.Path(name) for name in names if name and (root / name).is_file()]
errors = []
private_dirs = {'.private', 'private', 'internal', 'node_modules', '.venv', 'build', 'dist', '.gradle'}
private_extensions = {'.jks', '.keystore', '.p12', '.pfx', '.key', '.pem', '.sqlite', '.sqlite3', '.db', '.log', '.jsonl'}
private_names = {'pairing.json', 'android-sdk-report.json', 'community-launch.md',
                 'discord_post.md', 'demo.md', 'render_brand.mjs', 'score_motion.py', 'community_metrics.py'}
private_prefixes = ('sdk-token', 'muse-sdk-token', 'cookies', 'film-plan', 'motion-study', 'growth-')
for file in files:
    lower = str(file).lower()
    name = file.name.lower()
    if (set(part.lower() for part in file.parts) & private_dirs or lower.startswith('docs/validation/')
            or file.suffix.lower() in private_extensions or name in private_names
            or name.startswith(private_prefixes) or 'storage-state' in name
            or ((name == '.env' or name.startswith('.env.')) and name != '.env.example')):
        errors.append(f'{file}: private file class')
    if (root / file).is_symlink():
        errors.append(f'{file}: symlinks require a separate publication review')
    if file.suffix.lower() in {'.md', '.json', '.txt', '.html', '.yml', '.yaml'}:
        content = (root / file).read_text(errors='replace')
        # Device/account identifiers and local workstation paths belong in private evidence.
        if re.search(r'(?i)homelink-[0-9a-f]{6}\b|MuseGadget[0-9a-f]{6}\b|\b(?:10\.\d+|192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d+\.\d+\b|/Users/[^/\s]+/|/Volumes/[^\n]+/', content):
            errors.append(f'{file}: possible personal device or workstation identifier')

manifest = json.loads((root / 'docs/img/assets.json').read_text())
registered = set()
for entry in manifest['assets']:
    file = pathlib.Path(entry['path'])
    registered.add(str(file))
    if not (root / file).is_file():
        errors.append(f'{file}: missing reviewed asset')
    elif hashlib.sha256((root / file).read_bytes()).hexdigest() != entry['sha256']:
        errors.append(f'{file}: changed since media review; update manifest after review')
for file in files:
    if (str(file).startswith('docs/img/') or str(file) == 'docs/social-preview.png') and file.suffix.lower() in {'.png', '.jpg', '.jpeg', '.gif', '.mp4', '.webm', '.svg'}:
        if str(file) not in registered:
            errors.append(f'{file}: unreviewed public media')
if errors:
    print('\n'.join(errors), file=sys.stderr)
    sys.exit(1)
print(f'Public-file policy passed: {len(files)} files, {len(registered)} reviewed media assets')
