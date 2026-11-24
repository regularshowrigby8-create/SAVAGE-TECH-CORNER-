#!/usr/bin/env python3
"""List titles missing poster or backdrop art."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Titles with no poster or backdrop", group="catalogue")
def missing_art(settings=None):
    from nova.catalog import CINEMETA, fetch_catalog
    titles = fetch_catalog(CINEMETA, "movie", "top")
    return [t.as_dict() for t in titles if not t.poster or not t.background]


if __name__ == "__main__":
    raise SystemExit(main(__file__, "List titles missing poster or backdrop art.", REGISTRY))

