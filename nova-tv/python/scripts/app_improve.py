#!/usr/bin/env python3
"""Audit the Android app for defects that compile but ship broken."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry

# Each script owns its registry so tool names never collide across files.
REGISTRY = Registry()


@REGISTRY.register(summary="Audit the Android app for unwired and broken code", group="improve")
def improve(settings=None):
    from nova import appaudit
    from nova.config import ROOT

    android_root = ROOT / "android"
    findings = appaudit.audit(android_root)
    summary = appaudit.summarise(findings)
    return {
        "scanned": len(appaudit.kotlin_files(android_root)),
        "summary": summary,
        "findings": [f.as_dict() for f in findings],
        "plan": appaudit.plan(findings),
        "ok": True,
    }


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Audit the Android app for unwired and broken code", REGISTRY))
