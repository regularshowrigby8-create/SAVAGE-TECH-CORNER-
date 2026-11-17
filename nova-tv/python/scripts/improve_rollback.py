#!/usr/bin/env python3
"""Restore the most recent snapshot."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Roll back the last applied pass", group="improve")
def rollback(settings=None):
    from nova.selfimprove import Snapshot
    from nova.config import ROOT
    base = ROOT / ".nova-snapshots"
    if not base.exists():
        return {"restored": [], "note": "no snapshots present"}
    latest = sorted(p for p in base.iterdir() if p.is_dir())[-1]
    snap = Snapshot(root=ROOT, stamp=latest.name)
    for file in (latest.rglob("*")):
        if file.is_file() and file.name != "manifest.json":
            snap.files[str(file.relative_to(latest))] = ""
    return {"restored": snap.restore()}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Restore the most recent snapshot.", REGISTRY))

