#!/usr/bin/env python3
"""Report the largest functions by line count."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Largest functions by length", group="qa")
def complexity(settings=None):
    import ast
    from nova.config import ROOT
    rows = []
    for path in (ROOT / "python").rglob("*.py"):
        try:
            tree = ast.parse(path.read_text("utf-8"))
        except SyntaxError:
            continue
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                length = (node.end_lineno or node.lineno) - node.lineno
                rows.append({"name": node.name, "file": path.name, "lines": length})
    return sorted(rows, key=lambda r: r["lines"], reverse=True)[:25]


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Report the largest functions by line count.", REGISTRY))

