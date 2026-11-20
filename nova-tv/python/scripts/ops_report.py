#!/usr/bin/env python3
"""Write a full status report to reports/status.json."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Write a status report", group="ops")
def report(settings=None):
    from nova.config import ROOT, redacted_view
    from nova.report import write_json
    from nova.selfimprove import run_tests
    settings = settings or load_settings()
    passed, _ = run_tests(ROOT)
    payload = {"tools": len(REGISTRY), "tests_green": passed, "config": redacted_view(settings)}
    path = write_json("status.json", payload)
    return {"written": str(path), **{k: v for k, v in payload.items() if k != "config"}}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Write a full status report to reports/status.json.", REGISTRY))

