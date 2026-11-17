#!/usr/bin/env python3
"""Drop repos that no longer exist on GitHub."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Prune unreachable repos from the knowledge base", group="knowledge", needs_github=True)
def prune(settings=None):
    from nova.collector import KnowledgeBase
    from nova.github import GitHub, GitHubError
    settings = settings or load_settings()
    path = settings.cache_dir.parent / "knowledge.json"
    kb = KnowledgeBase.load(path)
    gh = GitHub(settings.github_token, settings.user_agent, settings.timeout, settings.github_ref)
    removed = []
    for name in list(kb.repos):
        try:
            gh.repo(name)
        except GitHubError:
            removed.append(name)
            del kb.repos[name]
    kb.save(path)
    return {"removed": removed, "remaining": len(kb.repos)}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Drop repos that no longer exist on GitHub.", REGISTRY))

