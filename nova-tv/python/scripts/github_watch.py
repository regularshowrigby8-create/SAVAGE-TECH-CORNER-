#!/usr/bin/env python3
"""Continuously collect on an interval until stopped."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Loop the collector on an interval", group="github", needs_github=True)
def watch(settings=None, interval=30):
    import time
    from nova.collector import DEFAULT_QUERIES, KnowledgeBase, collect as run
    from nova.github import GitHub
    settings = settings or load_settings()
    path = settings.cache_dir.parent / "knowledge.json"
    gh = GitHub(settings.github_token, settings.user_agent, settings.timeout)
    passes, discovered = 0, 0
    while True:
        kb = KnowledgeBase.load(path)
        report = run(gh, DEFAULT_QUERIES, kb, budget=2)
        kb.save(path)
        passes += 1
        discovered += report["new_count"]
        print(f"pass {passes}: +{report['new_count']} (total {report['total_known']})")
        time.sleep(max(5, interval))


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Continuously collect on an interval until stopped.", REGISTRY))

