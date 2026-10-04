"""Shared app setup: state paths and logging, configured once.

Both the service and pairing entry points go through here so the state
directory layout and log format can't drift between them.
"""

from __future__ import annotations

import logging
import os


def configure(files_dir: str, cache_dir: str | None = None) -> None:
    os.environ["MUSEGADGET_STATE_DIR"] = os.path.join(files_dir, "musegadget")
    if cache_dir is not None:
        os.environ["MUSEGADGET_SOCKET"] = os.path.join(cache_dir, "musegadget.sock")
    if not logging.getLogger().handlers:
        logging.basicConfig(
            level=logging.INFO,
            format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        )
