#!/usr/bin/env python3
"""Byte-compile every Python module."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Byte-compile all Python sources", group="qa")
def compile_all(settings=None):
    import compileall
    from nova.config import ROOT
    ok = compileall.compile_dir(str(ROOT / "python" / "nova"), quiet=2)
    ok = compileall.compile_dir(str(ROOT / "python" / "scripts"), quiet=2) and ok
    return {"ok": bool(ok)}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Byte-compile every Python module.", REGISTRY))

