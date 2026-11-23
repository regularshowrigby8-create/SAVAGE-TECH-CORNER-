#!/usr/bin/env python3
"""Find duplicate titles across feeds."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Detect duplicate entries in the catalogue", group="catalogue")
def duplicates(settings=None):
    from nova.catalog import CINEMETA, fetch_catalog
    titles = fetch_catalog(CINEMETA, "movie", "top")
    seen, dupes = set(), []
    for title in titles:
        key = title.imdb_id or title.id
        if key in seen:
            dupes.append(title.name)
        seen.add(key)
    return {"duplicates": dupes, "count": len(dupes)}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Find duplicate titles across feeds.", REGISTRY))

