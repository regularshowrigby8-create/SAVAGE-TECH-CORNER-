#!/usr/bin/env python3
"""Import player timings and the Android shell layout from the existing repo."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()


@REGISTRY.register(summary="Import player-core constants and the android shell layout",
                  group="import", needs_github=True)
def import_all(settings=None):
    from nova.config import ROOT
    from nova.github import GitHub
    from nova.importers import run_all
    settings = settings or load_settings()
    gh = GitHub(settings.github_token, settings.user_agent, settings.timeout, settings.github_ref) if settings.has_github else None
    # ROOT is .../nova-tv: sources are read above it, output is written into it.
    results = run_all(gh, ROOT)
    return {
        "results": [
            {"key": r.key, "ok": r.ok, "detail": r.detail, "written": r.written}
            for r in results
        ],
        "ok": all(r.ok for r in results),
    }


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Import player timings and android shell layout", REGISTRY))
