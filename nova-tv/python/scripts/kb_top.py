#!/usr/bin/env python3
"""Rank collected repositories by stars."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Top repositories by stars", group="knowledge")
def top(limit=20, settings=None):
    from nova.collector import KnowledgeBase
    settings = settings or load_settings()
    kb = KnowledgeBase.load(settings.cache_dir.parent / "knowledge.json")
    ranked = sorted(kb.repos.values(), key=lambda e: e.get("stars", 0), reverse=True)
    return ranked[:limit]


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Rank collected repositories by stars.", REGISTRY))

