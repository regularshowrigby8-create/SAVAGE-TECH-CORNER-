#!/usr/bin/env python3
"""Show remaining GitHub API budget."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="GitHub rate-limit budget", group="github", needs_github=True)
def rate(settings=None):
    from nova.github import GitHub
    settings = settings or load_settings()
    payload = GitHub(settings.github_token, settings.user_agent, settings.timeout).rate_limit()
    core = (payload.get("resources") or {}).get("core") or {}
    return {"remaining": core.get("remaining"), "limit": core.get("limit")}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Show remaining GitHub API budget.", REGISTRY))

