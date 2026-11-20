#!/usr/bin/env python3
"""Print the resolved configuration with secrets masked."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Resolved configuration, secrets masked", group="ops")
def env(settings=None):
    from nova.config import redacted_view
    settings = settings or load_settings()
    return redacted_view(settings)


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Print the resolved configuration with secrets masked.", REGISTRY))

