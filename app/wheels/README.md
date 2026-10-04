# Zeroconf pure-Python wheel

`zeroconf-0.149.16-py3-none-any.whl` contains the unmodified Python sources from
PyPI's `zeroconf==0.149.16` source distribution. Its optional Cython extensions
are disabled with the upstream `SKIP_CYTHON=1` build option. The build still
produces a platform-tagged wheel; `scripts/build_zeroconf_wheel.py` verifies that
no native libraries exist, corrects only wheel metadata/tags, and regenerates
RECORD hashes. Runtime package source is not patched.

This replaces the old 0.39.4 wheel that Chaquopy selected by default. That version
has known mDNS parsing/resource-limit advisories. Cast discovery itself uses
Android NSD, but PyChromecast imports zeroconf, so we ship the updated dependency.
The wheel includes its upstream LGPL license.

Sources: [upstream build hook](https://github.com/python-zeroconf/python-zeroconf/blob/0.149.16/build_ext.py),
[PyPI release](https://pypi.org/project/zeroconf/0.149.16/).

Bundled wheel SHA-256:
`f46ddf85d2444d494704bbe3de4c429ea4c9109c86ba983e7f5980640b8cf38d`

Rebuild from the repository root with a Python environment containing pip:

```sh
python3.11 scripts/build_zeroconf_wheel.py
```

The build tool versions and ZIP timestamps can change the archive hash even when
package source is identical; inspect and record a new hash before replacing the
checked-in artifact. This wheel is not downloaded from an unofficial binary host.
