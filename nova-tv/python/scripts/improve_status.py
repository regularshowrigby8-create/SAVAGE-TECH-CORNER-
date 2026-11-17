#!/usr/bin/env python3
"""Show git state and whether apply is safe."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Self-improvement readiness", group="improve")
def status(settings=None):
    from nova.config import ROOT
    from nova.selfimprove import git_head, git_is_clean, run_tests
    passed, _ = run_tests(ROOT)
    return {"head": git_head(ROOT), "clean": git_is_clean(ROOT), "tests_green": passed}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Show git state and whether apply is safe.", REGISTRY))

