#!/usr/bin/env python3
"""Show the current commit for a branch."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="HEAD commit for a repository branch", group="github", needs_github=True)
def head(settings=None, repo="regularshowrigby8-create/SAVAGE-TECH-CORNER-", branch="main"):
    from nova.github import GitHub
    settings = settings or load_settings()
    gh = GitHub(settings.github_token, settings.user_agent, settings.timeout)
    payload = gh.get(f"/repos/{repo}/commits", {"sha": branch, "per_page": 1})
    return payload[0] if payload else {}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Show the current commit for a branch.", REGISTRY))

