#!/usr/bin/env python3
"""Apply green proposals (requires --apply)."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry
from nova.config import load_settings

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()

@REGISTRY.register(summary="Apply validated proposals", group="improve")
def apply(settings=None):
    from nova.selfimprove import GuardError, Loop
    from nova.config import ROOT
    try:
        loop = Loop(root=ROOT, allow_apply=True)
        return {"written": loop.apply([]), "report": loop.report()}
    except GuardError as error:
        return {"blocked": str(error)}


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Apply green proposals (requires --apply).", REGISTRY))

