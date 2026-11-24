#!/usr/bin/env python3
"""Remove caches and build artifacts."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Remove caches and artifacts", group="ops")
def clean(settings=None):
    import shutil
    from nova.config import ROOT
    targets = [settings.cache_dir if settings else None, ROOT / "python" / "novaa" / "__pycache__"]
    settings = settings or load_settings()
    removed = []
    for target in (settings.cache_dir,):
        if target and target.exists():
            shutil.rmtree(target, ignore_errors=True)
            removed.append(str(target))
    for pycache in ROOT.rglob("__pycache__"):
        shutil.rmtree(pycache, ignore_errors=True)
    return {"removed": removed}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Remove caches and build artifacts.", REGISTRY))

