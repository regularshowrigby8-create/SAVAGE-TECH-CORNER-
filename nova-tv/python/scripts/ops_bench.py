#!/usr/bin/env python3
"""Time the pure functions to catch regressions."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Micro-benchmark core functions", group="ops")
def bench(settings=None):
    import time
    from nova.catalog import merge_feeds, parse_meta, search_local
    raw = {"imdb_id": "tt1", "name": "Bench", "type": "movie", "imdbRating": "7.0"}
    start = time.perf_counter()
    for _ in range(1000):
        parse_meta(raw)
    parse_ms = (time.perf_counter() - start) * 1000
    titles = [parse_meta({**raw, "imdb_id": f"tt{i}", "name": f"T{i}"}) for i in range(500)]
    start = time.perf_counter()
    for _ in range(100):
        search_local(titles, "T4")
        merge_feeds(titles)
    search_ms = (time.perf_counter() - start) * 1000
    return {"parse_1000_ms": round(parse_ms, 2), "search_merge_100_ms": round(search_ms, 2)}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Time the pure functions to catch regressions.", REGISTRY))

