#!/usr/bin/env python3
"""Ask four independent models, through ZeroKey, to check one claim."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import main
from nova.registry import Registry

REGISTRY = Registry()


@REGISTRY.register(summary="Cross-check a claim with four models via ZeroKey", group="verify")
def four_models(claim: str = "The app compiles to a working debug APK.", evidence: str = "", settings=None):
    from nova import zerokey

    client = zerokey.ZeroKey()
    status = zerokey.status()
    if not status["reachable"]:
        return {
            "reachable": False,
            "hint": (
                "ZeroKey is not running. Start it from "
                "github.com/downloaddoctor/zerokey and it will serve "
                "http://localhost:7250/v1."
            ),
            "status": status,
            "ok": False,
        }
    consensus = client.verify(claim, evidence)
    return {
        "reachable": True,
        "providers_found": status["providers_found"],
        "providers_missing": status["providers_missing"],
        "consensus": consensus.as_dict(),
        "ok": True,
    }


if __name__ == "__main__":
    raise SystemExit(main(__file__, "Cross-check a claim with four models", REGISTRY))
