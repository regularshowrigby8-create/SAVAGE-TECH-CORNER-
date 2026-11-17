#!/usr/bin/env python3
"""List every registered tool with its requirements."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Tool inventory", group="ops")
def inventory(settings=None):
    return REGISTRY.manifest()


if __name__ == "__main__":
    raise SystemExit(main(__file__, "List every registered tool with its requirements.", REGISTRY))

