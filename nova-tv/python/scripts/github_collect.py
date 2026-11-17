#!/usr/bin/env python3
"""One resumable collection pass over GitHub search."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Run one GitHub collection pass", group="github", needs_github=True)
def collect(settings=None, budget=2):
    from nova.collector import DEFAULT_QUERIES, KnowledgeBase, collect as run
    from nova.github import GitHub
    settings = settings or load_settings()
    kb = KnowledgeBase.load(settings.cache_dir.parent / "knowledge.json")
    gh = GitHub(settings.github_token, settings.user_agent, settings.timeout)
    report = run(gh, DEFAULT_QUERIES, kb, budget=budget)
    kb.save(settings.cache_dir.parent / "knowledge.json")
    return report


if __name__ == "__main__":
    raise SystemExit(main(__file__, "One resumable collection pass over GitHub search.", REGISTRY))

