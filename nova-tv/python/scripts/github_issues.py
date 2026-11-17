#!/usr/bin/env python3
"""List open issues on a repository."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Open issues for a repository", group="github", needs_github=True)
def issues(settings=None, repo="regularshowrigby8-create/SAVAGE-TECH-CORNER-"):
    from nova.github import GitHub
    settings = settings or load_settings()
    gh = GitHub(settings.github_token, settings.user_agent, settings.timeout, settings.github_ref)
    return gh.get(f"/repos/{repo}/issues", {"state": "open", "per_page": 30})


if __name__ == "__main__":
    raise SystemExit(main(__file__, "List open issues on a repository.", REGISTRY))

