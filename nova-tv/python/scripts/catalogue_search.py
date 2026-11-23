#!/usr/bin/env python3
"""Search the fetched catalogue by title."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

FLAGS = [(["--query", "-q"], {"default": "", "help": "text to search for"}),
         (["--limit", "-n"], {"type": int, "default": 20})]


@REGISTRY.register(summary="Search locally indexed catalogue titles", group="catalogue")
def search(query="", limit=20, settings=None):
    from nova.catalog import CINEMETA, fetch_catalog, search_local
    ids = search_local([], "")
    if query:
        titles = fetch_catalog(CINEMETA, "movie", "top")
        hits = search_local(titles, query, limit=limit)
        return [t.as_dict() for t in hits]
    return []


for flag in FLAGS:
    search.__nova_flags__ = getattr(search, "__nova_flags__", []) + [flag]


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Search the fetched catalogue by title.", REGISTRY))

