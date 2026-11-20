#!/usr/bin/env python3
"""Basic lint: long lines, tabs, trailing whitespace."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Style issues across Python sources", group="qa")
def lint(settings=None):
    from nova.config import ROOT
    issues = []
    for path in (ROOT / "python").rglob("*.py"):
        for number, line in enumerate(path.read_text("utf-8").splitlines(), 1):
            if len(line) > 120:
                issues.append(f"{path.name}:{number} line too long ({len(line)})")
            if "\t" in line:
                issues.append(f"{path.name}:{number} tab character")
            if line != line.rstrip():
                issues.append(f"{path.name}:{number} trailing whitespace")
    return {"issues": issues[:100], "count": len(issues)}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Basic lint: long lines, tabs, trailing whitespace.", REGISTRY))

