#!/usr/bin/env python3
"""List the source files of a repository."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Source files in a repository", group="github", needs_github=True)
def tree(settings=None, repo="regularshowrigby8-create/SAVAGE-TECH-CORNER-"):
    from nova.github import GitHub, code_paths
    settings = settings or load_settings()
    gh = GitHub(settings.github_token, settings.user_agent, settings.timeout, settings.github_ref)
    return code_paths(gh.tree(repo), extensions=(".kt", ".py", ".kts"))


if __name__ == "__main__":
    raise SystemExit(main(__file__, "List the source files of a repository.", REGISTRY))

