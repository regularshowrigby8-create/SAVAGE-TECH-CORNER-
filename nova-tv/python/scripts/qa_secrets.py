#!/usr/bin/env python3
"""Fail if any file appears to contain a live credential."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Scan for committed credentials", group="qa")
def secrets(settings=None):
    import re
    from nova.config import ROOT
    patterns = [
        re.compile(r"ghp_[A-Za-z0-9]{36}"),
        re.compile(r"sk-[A-Za-z0-9]{20,}"),
        re.compile(r"AKIA[0-9A-Z]{16}"),
        re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    ]
    hits = []
    skip = {".git", "node_modules", ".nova-cache", "build", ".gradle"}
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.suffix in {".pyc", ".jar", ".apk"}:
            continue
        if any(part in skip for part in path.parts):
            continue
        try:
            text = path.read_text("utf-8", errors="ignore")
        except OSError:
            continue
        for pattern in patterns:
            if pattern.search(text):
                hits.append(str(path.relative_to(ROOT)))
                break
    return {"files_with_secrets": hits, "count": len(hits)}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Fail if any file appears to contain a live credential.", REGISTRY))

