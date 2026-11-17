#!/usr/bin/env python3
"""Run the project test suite."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Run the test suite", group="qa")
def tests(settings=None):
    from nova.config import ROOT
    from nova.selfimprove import run_tests
    passed, output = run_tests(ROOT)
    return {"passed": passed, "tail": output[-1500:]}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Run the project test suite.", REGISTRY))

