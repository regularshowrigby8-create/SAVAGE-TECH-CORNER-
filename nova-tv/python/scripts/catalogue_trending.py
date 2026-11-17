#!/usr/bin/env python3
"""List today's trending titles from the public Meta feeds."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Trending titles from the public catalogue feeds", group="catalogue", needs_network=True)
def trending(settings=None):
    from nova.catalog import CINEMETA, fetch_catalog, merge_feeds
    settings = settings or load_settings()
    movies = fetch_catalog(CINEMETA, "movie", "top", timeout=settings.timeout)
    series = fetch_catalog(CINEMETA, "series", "top", timeout=settings.timeout)
    merged = merge_feeds([*movies, *series])
    return [t.as_dict() for t in merged[:40]]


if __name__ == "__main__":
    raise SystemExit(main(__file__, "List today's trending titles from the public Meta feeds.", REGISTRY))

