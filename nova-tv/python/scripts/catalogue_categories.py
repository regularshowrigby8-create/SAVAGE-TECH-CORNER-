#!/usr/bin/env python3
"""Report the categories available across feeds."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Categories present in the cached catalogue", group="catalogue")
def categories(settings=None):
    from nova.catalog import CINEMETA, fetch_catalog
    titles = fetch_catalog(CINEMETA, "movie", "top")
    counts = {}
    for title in titles:
        for genre in title.genres:
            counts[genre] = counts.get(genre, 0) + 1
    return dict(sorted(counts.items(), key=lambda kv: kv[1], reverse=True))


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Report the categories available across feeds.", REGISTRY))

