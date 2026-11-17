#!/usr/bin/env python3
"""Export the catalogue to a local JSON file."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Write the current catalogue to reports/catalogue.json", group="catalogue")
def export(settings=None):
    from nova.catalog import CINEMETA, fetch_catalog
    from nova.report import write_json
    titles = fetch_catalog(CINEMETA, "movie", "top")
    path = write_json("catalogue.json", [t.as_dict() for t in titles])
    return {"written": str(path), "count": len(titles)}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Export the catalogue to a local JSON file.", REGISTRY))

