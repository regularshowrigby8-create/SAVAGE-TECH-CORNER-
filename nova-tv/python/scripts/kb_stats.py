#!/usr/bin/env python3
"""Summarise the collected knowledge base."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Knowledge-base statistics", group="knowledge")
def stats(settings=None):
    from nova.collector import KnowledgeBase
    settings = settings or load_settings()
    kb = KnowledgeBase.load(settings.cache_dir.parent / "knowledge.json")
    return {"count": len(kb.repos), "languages": kb.by_language()}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Summarise the collected knowledge base.", REGISTRY))

