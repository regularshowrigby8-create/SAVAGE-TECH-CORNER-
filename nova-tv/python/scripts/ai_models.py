#!/usr/bin/env python3
"""List the configured model aliases."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Configured model aliases", group="ai")
def models(settings=None):
    from nova.ai import load_model_registry
    return {alias: {"model": s.model, "base_url": s.base_url} for alias, s in load_model_registry().items()}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "List the configured model aliases.", REGISTRY))

