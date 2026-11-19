#!/usr/bin/env python3
"""Find controls that look interactive and do nothing."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry

REGISTRY = Registry()


@REGISTRY.register(summary="Find dead buttons and no-op handlers", group="verify")
def buttons(settings=None):
    from nova import buttons as btns
    from nova.config import ROOT

    findings = btns.audit(ROOT)
    summary = btns.summarise(findings)
    return {
        "summary": summary,
        "findings": [f.as_dict() for f in findings],
        # A dead button found is a failed check, not a pass with notes.
        "ok": summary["ok"],
    }


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Find dead buttons", REGISTRY))
