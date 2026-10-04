#!/usr/bin/env python3
"""Build upstream's optional-extension-free wheel and correct its wheel tags."""
import base64
import csv
import hashlib
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

VERSION='0.149.16'
destination=Path(__file__).resolve().parents[1]/'app'/'wheels'
with tempfile.TemporaryDirectory() as directory:
    subprocess.run([sys.executable,'-m','pip','wheel','--no-deps','--no-binary=zeroconf',f'zeroconf=={VERSION}','-w',directory],env={**os.environ,'SKIP_CYTHON':'1'},check=True)
    source=next(Path(directory).glob('zeroconf-*.whl'))
    with zipfile.ZipFile(source) as archive:
        files={name:archive.read(name) for name in archive.namelist()}
    if any(name.endswith(('.so','.pyd','.dylib')) or data.startswith(b'\x7fELF') for name,data in files.items()):
        raise SystemExit('Refusing to mark a native wheel as pure Python')
    wheel=next(name for name in files if name.endswith('/WHEEL'))
    record=next(name for name in files if name.endswith('/RECORD'))
    metadata=files[wheel].decode().splitlines()
    files[wheel]=('\n'.join('Tag: py3-none-any' if line.startswith('Tag:') else 'Root-Is-Purelib: true' if line.startswith('Root-Is-Purelib:') else line for line in metadata)+'\n').encode()
    output=io.StringIO(); writer=csv.writer(output,lineterminator='\n')
    for name,data in files.items():
        if name!=record:
            digest=base64.urlsafe_b64encode(hashlib.sha256(data).digest()).decode().rstrip('=')
            writer.writerow([name,'sha256='+digest,len(data)])
    writer.writerow([record,'','']); files[record]=output.getvalue().encode()
    destination.mkdir(exist_ok=True)
    target=destination/f'zeroconf-{VERSION}-py3-none-any.whl'
    with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as archive:
        for name,data in files.items(): archive.writestr(name,data)
    print(target,hashlib.sha256(target.read_bytes()).hexdigest())
