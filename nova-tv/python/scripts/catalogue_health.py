#!/usr/bin/env python3
"""Check that every configured feed answers."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Probe each known catalogue feed", group="catalogue", needs_network=True)
def health(settings=None):
    from nova.catalog import reachable_hosts
    settings = settings or load_settings()
    return reachable_hosts(timeout=min(8.0, settings.timeout))


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Check that every configured feed answers.", REGISTRY))

