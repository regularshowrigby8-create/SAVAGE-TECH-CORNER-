#!/usr/bin/env python3
"""Check the environment is ready to run everything."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Environment readiness report", group="ops")
def doctor(settings=None):
    import shutil, sys
    from nova.config import ROOT
    settings = settings or load_settings()
    checks = [
        {"check": "python>=3.11", "ok": sys.version_info >= (3, 11), "detail": sys.version.split()[0]},
        {"check": "git present", "ok": shutil.which("git") is not None},
        {"check": "github token", "ok": settings.has_github, "detail": "needed for collection"},
        {"check": "ai key", "ok": settings.has_ai, "detail": "optional; aliases still resolve"},
        {"check": "tests dir", "ok": (ROOT / "python" / "tests").is_dir()},
        {"check": "scripts dir", "ok": (ROOT / "python" / "scripts").is_dir()},
    ]
    return {"checks": checks, "ok": all(c["ok"] for c in checks if c["check"] in ("python>=3.11", "git present"))}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Check the environment is ready to run everything.", REGISTRY))

