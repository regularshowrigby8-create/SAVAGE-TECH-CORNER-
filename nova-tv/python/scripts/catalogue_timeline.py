#!/usr/bin/env python3
"""Group the catalogue by release year."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Catalogue counts bucketed by year", group="catalogue")
def timeline(settings=None):
    from nova.catalog import CINEMETA, fetch_catalog
    titles = fetch_catalog(CINEMETA, "movie", "top")
    buckets = {}
    for title in titles:
        year = title.year or "unknown"
        buckets[year] = buckets.get(year, 0) + 1
    return dict(sorted(buckets.items(), reverse=True))


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Group the catalogue by release year.", REGISTRY))

