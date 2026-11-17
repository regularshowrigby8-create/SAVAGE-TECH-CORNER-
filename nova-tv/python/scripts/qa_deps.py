#!/usr/bin/env python3
"""Report third-party imports so dependencies stay explicit."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Third-party import inventory", group="qa")
def deps(settings=None):
    import ast, sys
    from nova.config import ROOT
    stdlib = set(sys.stdlib_module_names)
    external = set()
    for path in (ROOT / "python").rglob("*.py"):
        try:
            tree = ast.parse(path.read_text("utf-8"))
        except SyntaxError:
            continue
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    root = alias.name.split(".")[0]
                    if root not in stdlib:
                        external.add(root)
            elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
                root = node.module.split(".")[0]
                if root not in stdlib:
                    external.add(root)
    return {"external": sorted(external), "note": "nova itself is stdlib-only by design"}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Report third-party imports so dependencies stay explicit.", REGISTRY))

