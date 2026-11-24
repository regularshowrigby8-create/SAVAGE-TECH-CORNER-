#!/usr/bin/env python3
"""Export the knowledge base to JSON."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Export the knowledge base", group="knowledge")
def export(settings=None):
    from nova.collector import KnowledgeBase
    from nova.report import write_json
    settings = settings or load_settings()
    kb = KnowledgeBase.load(settings.cache_dir.parent / "knowledge.json")
    path = write_json("knowledge.json", kb.to_dict())
    return {"written": str(path), "count": len(kb.repos)}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Export the knowledge base to JSON.", REGISTRY))

