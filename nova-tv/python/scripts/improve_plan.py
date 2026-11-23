#!/usr/bin/env python3
"""Produce an improvement plan without changing files."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Dry-run improvement pass", group="improve")
def plan(settings=None):
    from nova.selfimprove import Loop
    from nova.config import ROOT
    loop = Loop(root=ROOT, allow_apply=False)
    loop.record("dry run: no files will be written")
    passed, output = __import__("nova.selfimprove", fromlist=["run_tests"]).run_tests(ROOT)
    loop.record("tests green" if passed else "tests failing")
    return loop.report()


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Produce an improvement plan without changing files.", REGISTRY))

