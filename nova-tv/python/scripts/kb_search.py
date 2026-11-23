#!/usr/bin/env python3
"""Search collected repositories by name or topic."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Search the knowledge base", group="knowledge")
def search(query="", settings=None):
    from nova.collector import KnowledgeBase
    settings = settings or load_settings()
    kb = KnowledgeBase.load(settings.cache_dir.parent / "knowledge.json")
    return kb.search(query)[:25]


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Search collected repositories by name or topic.", REGISTRY))

