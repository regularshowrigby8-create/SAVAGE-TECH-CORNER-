#!/usr/bin/env python3
"""Verify every script is executable and importable."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Verify the script set", group="ops")
def check_links(settings=None):
    import importlib.util
    from nova.config import ROOT
    scripts = sorted((ROOT / "python" / "scripts").glob("*.py"))
    broken = []
    for path in scripts:
        try:
            spec = importlib.util.spec_from_file_location(path.stem, path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
        except Exception as error:
            broken.append({"script": path.name, "error": str(error)})
    return {"scripts": len(scripts), "broken": broken}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Verify every script is executable and importable.", REGISTRY))

