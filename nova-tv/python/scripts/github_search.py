#!/usr/bin/env python3
"""Search GitHub repositories directly."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Search GitHub repositories", group="github", needs_github=True)
def search(settings=None, query="stremio addon", limit=10):
    from nova.github import GitHub
    settings = settings or load_settings()
    gh = GitHub(settings.github_token, settings.user_agent, settings.timeout)
    return [r.as_dict() for r in gh.search_repos(query, limit=limit)]


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Search GitHub repositories directly.", REGISTRY))

