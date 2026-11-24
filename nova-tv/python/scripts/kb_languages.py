#!/usr/bin/env python3
"""Show the language distribution of collected repos."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Language distribution of collected repos", group="knowledge")
def languages(settings=None):
    from nova.collector import KnowledgeBase
    settings = settings or load_settings()
    return KnowledgeBase.load(settings.cache_dir.parent / "knowledge.json").by_language()


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Show the language distribution of collected repos.", REGISTRY))

