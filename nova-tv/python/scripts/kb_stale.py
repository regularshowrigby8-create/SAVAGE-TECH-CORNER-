#!/usr/bin/env python3
"""Find repos not updated in a long time."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Stale repositories in the knowledge base", group="knowledge")
def stale(days=365, settings=None):
    from nova.collector import KnowledgeBase
    settings = settings or load_settings()
    kb = KnowledgeBase.load(settings.cache_dir.parent / "knowledge.json")
    return [e for e in kb.repos.values() if str(e.get("updated_at", "")) < f"20{25}"]


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Find repos not updated in a long time.", REGISTRY))

