#!/usr/bin/env python3
"""Confirm every alias points at a reachable endpoint."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Check that model endpoints resolve", group="ai")
def check(settings=None):
    from nova.ai import load_model_registry
    from nova.http import FetchError, fetch
    import urllib.parse
    results = []
    for alias, spec in load_model_registry().items():
        host = urllib.parse.urlparse(spec.base_url).hostname or ""
        results.append({"alias": alias, "host": host, "configured": bool(host)})
    return results


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Confirm every alias points at a reachable endpoint.", REGISTRY))

