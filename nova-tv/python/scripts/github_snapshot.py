#!/usr/bin/env python3
"""Snapshot a repo's files for later diffing."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Snapshot a repository's key files", group="github", needs_github=True)
def snapshot(settings=None, repo="regularshowrigby8-create/SAVAGE-TECH-CORNER-"):
    from nova.collector import snapshot_repo
    from nova.github import GitHub
    from nova.report import write_json
    settings = settings or load_settings()
    snap = snapshot_repo(GitHub(settings.github_token, settings.user_agent, settings.timeout), repo)
    write_json("snapshot-" + repo.replace("/", "_") + ".json", snap)
    return {"repo": repo, "files": len(snap["files"])}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Snapshot a repo's files for later diffing.", REGISTRY))

