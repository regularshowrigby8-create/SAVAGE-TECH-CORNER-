#!/usr/bin/env python3
"""Count the registered tools by group."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Tool count by group", group="ops")
def count(settings=None):
    return {"total": len(REGISTRY), "groups": {g: len(v) for g, v in REGISTRY.groups().items()}}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Count the registered tools by group.", REGISTRY))

