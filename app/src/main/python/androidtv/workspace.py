"""Bounded chunked files beneath one directory, without following symlinks.

Directory descriptors keep validation and IO at the same seam. In particular,
the temporary write file is protected as well as the final destination.
"""
from __future__ import annotations

import base64
import hashlib
import os
import stat
import threading
from contextlib import contextmanager
from pathlib import Path

CHUNK = 65536
MAX_FILE = 64 * 1024 * 1024


class Workspace:
    def __init__(self, root):
        self.root = Path(root).absolute()
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self._lock = threading.RLock()

    def parts(self, path):
        if not isinstance(path, str) or not path or "\x00" in path:
            raise ValueError("path is required")
        if os.path.isabs(path):
            path = os.path.relpath(path, self.root)
        parts = path.split("/")
        if any(p in ("", ".", "..") or p.startswith(".") for p in parts):
            raise ValueError("that path is outside the workspace or is reserved")
        return parts

    @contextmanager
    def parent(self, path, create=False):
        parts = self.parts(path)
        fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            for part in parts[:-1]:
                if create:
                    try:
                        os.mkdir(part, mode=0o700, dir_fd=fd)
                    except FileExistsError:
                        pass
                next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                os.close(fd)
                fd = next_fd
            yield fd, parts[-1]
        finally:
            os.close(fd)

    def read(self, params):
        offset, limit = int(params.get("offset", 0)), int(params.get("limit", CHUNK))
        if offset < 0 or not 1 <= limit <= CHUNK:
            raise ValueError("offset must be nonnegative; limit must be 1..65536")
        with self._lock, self.parent(params.get("path")) as (parent, name):
            fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
            with os.fdopen(fd, "rb") as f:
                info = os.fstat(f.fileno())
                if not stat.S_ISREG(info.st_mode):
                    raise ValueError("only regular workspace files may be read")
                f.seek(offset)
                data = f.read(limit)
        return {"path": params["path"], "size": info.st_size, "offset": offset,
                "next_offset": offset + len(data), "eof": offset + len(data) >= info.st_size,
                "data_b64": base64.b64encode(data).decode()}

    def write(self, params):
        offset = int(params.get("offset", 0))
        encoded = params.get("data_b64", "")
        if not isinstance(encoded, str) or len(encoded) > (CHUNK + 2) // 3 * 4:
            raise ValueError("chunk exceeds 64 KiB")
        data = base64.b64decode(encoded, validate=True)
        if offset < 0 or len(data) > CHUNK or offset + len(data) > MAX_FILE:
            raise ValueError("invalid offset or file exceeds 64 MiB")
        with self._lock, self.parent(params.get("path"), params.get("create_parents", False)) as (parent, name):
            partial = f".{name}.musegadget-partial"
            flags = os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK
            if offset == 0:
                flags |= os.O_CREAT | os.O_TRUNC
            fd = os.open(partial, flags, 0o600, dir_fd=parent)
            with os.fdopen(fd, "r+b") as f:
                if not stat.S_ISREG(os.fstat(f.fileno()).st_mode):
                    raise ValueError("only regular workspace files may be written")
                if f.seek(0, os.SEEK_END) != offset:
                    raise ValueError("chunk offset does not match current upload")
                f.write(data)
                written = f.tell()
                if not params.get("final", False):
                    return {"path": params["path"], "received": written, "complete": False}
                f.flush()
                os.fsync(f.fileno())
                f.seek(0)
                digest = hashlib.sha256()
                for block in iter(lambda: f.read(CHUNK), b""):
                    digest.update(block)
            actual = digest.hexdigest()
            if params.get("sha256") and params["sha256"].lower() != actual:
                os.unlink(partial, dir_fd=parent)
                raise ValueError("sha256 mismatch; upload discarded")
            if not params.get("overwrite", True):
                # link is an atomic no-replace commit, unlike exists+rename.
                try:
                    os.link(partial, name, src_dir_fd=parent, dst_dir_fd=parent, follow_symlinks=False)
                finally:
                    os.unlink(partial, dir_fd=parent)
            else:
                os.replace(partial, name, src_dir_fd=parent, dst_dir_fd=parent)
            return {"path": params["path"], "size": written, "sha256": actual, "complete": True}

    def discard_partial(self, path):
        try:
            with self._lock, self.parent(path) as (parent, name):
                os.unlink(f".{name}.musegadget-partial", dir_fd=parent)
        except FileNotFoundError:
            pass
