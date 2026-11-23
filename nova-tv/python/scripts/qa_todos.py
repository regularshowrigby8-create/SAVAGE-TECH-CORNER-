#!/usr/bin/env python3
"""Collect TODO/FIXME markers."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="TODO and FIXME markers", group="qa")
def todos(settings=None):
    from nova.config import ROOT
    found = []
    for path in list((ROOT / "python").rglob("*.py")) + list((ROOT / "android").rglob("*.kt")):
        for number, line in enumerate(path.read_text("utf-8", errors="replace").splitlines(), 1):
            for marker in ("TODO", "FIXME", "HACK"):
                if marker in line:
                    found.append({"file": path.name, "line": number, "text": line.strip()[:140]})
    return {"markers": found, "count": len(found)}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Collect TODO/FIXME markers.", REGISTRY))

