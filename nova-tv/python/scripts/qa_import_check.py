#!/usr/bin/env python3
"""Import every script module to catch errors early."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Import-check every script", group="qa")
def imports(settings=None):
    import importlib.util
    from nova.config import ROOT
    broken = []
    for path in sorted((ROOT / "python" / "scripts").glob("*.py")):
        spec = importlib.util.spec_from_file_location(path.stem, path)
        try:
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
        except Exception as error:
            broken.append({"script": path.name, "error": str(error)})
    return {"broken": broken, "checked": len(list((ROOT / "python" / "scripts").glob("*.py")))}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Import every script module to catch errors early.", REGISTRY))

