#!/usr/bin/env python3
"""Inspect all shipped ELF files, including Chaquopy's nested asset archives.

Usage: python3 scripts/check_apk.py app.apk [--require-16k]
A passing static check is necessary, but does not replace a 16 KiB device test.
"""
import argparse
import io
import json
import struct
import zipfile


def inspect(name, data):
    if data[:4] != b'\x7fELF':
        return None
    bits = data[4]
    endian = '<' if data[5] == 1 else '>'
    if bits == 2:
        offset = struct.unpack_from(endian+'Q',data,32)[0]
        size, count = struct.unpack_from(endian+'HH',data,54)
        fmt, align_index = endian+'IIQQQQQQ', 7
    else:
        offset = struct.unpack_from(endian+'I',data,28)[0]
        size, count = struct.unpack_from(endian+'HH',data,42)
        fmt, align_index = endian+'IIIIIIII', 7
    aligns=[]
    for i in range(count):
        ph=struct.unpack_from(fmt,data,offset+i*size)
        if ph[0] == 1:
            aligns.append(ph[align_index])
    return {'name':name,'bits':64 if bits==2 else 32,'load_alignment':min(aligns) if aligns else 0}


def scan(archive, prefix=''):
    found=[]
    with zipfile.ZipFile(archive) as package:
        for name in package.namelist():
            if '.so' in name.rsplit('/',1)[-1]:
                result=inspect(prefix+name,package.read(name))
                if result:found.append(result)
            elif name.endswith('.imy'):
                content=io.BytesIO(package.read(name))
                if zipfile.is_zipfile(content):
                    found.extend(scan(content,prefix+name+'!'))
    return found


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('apk'); parser.add_argument('--require-16k',action='store_true')
    args=parser.parse_args()
    libraries=scan(args.apk)
    blocked=[v for v in libraries if v['bits']==64 and v['load_alignment']<16384]
    print(json.dumps({'native_libraries':len(libraries),'alignment_16k':not blocked,'incompatible_64bit':blocked},indent=2))
    if not libraries or args.require_16k and blocked:raise SystemExit(1)

if __name__=='__main__':main()
