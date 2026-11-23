#!/usr/bin/env python3
"""Debug + hallucination + logic check for the translated finders.

Runs the offline checks in ``nova.translated_check``: files exist, no
stubs, logic constants match the Python originals, wiring is intact,
hallucinated references are caught, and tests are real (not false
green).

Exit code is the gate: 0 only when every check is proven, 1 when any
check is contradicted. That matters because the shared CLI prints the
payload and returns 0 regardless of the payload's own ``ok`` field, so
without this a failing check would still be a green CI step.

Usage:
    python3 scripts/qa_translated.py             # human summary, gate exit
    python3 scripts/qa_translated.py --json      # machine-readable
    python3 scripts/qa_translated.py --root DIR  # check another checkout
    python3 scripts/qa_translated.py --live      # also probe catalogues
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.cli import _flags, main
from nova.registry import Registry

REGISTRY = Registry()

# Set by the tool so __main__ can turn a contradicted check into exit 1.
FAILED = False


@REGISTRY.register(summary="Debug + hallucination + logic check for translated finders", group="qa")
@_flags(
    (("--root",), {"default": "", "help": "checkout to inspect (default: this repo)"}),
    (("--live",), {"action": "store_true", "help": "also probe catalogue URLs (needs network)"}),
)
def check(root: str = "", live: bool = False):
    global FAILED
    from nova import translated_check
    from nova.config import ROOT

    target = Path(root).expanduser().resolve() if root else ROOT
    checks = translated_check.run_all(target)
    if live:
        checks += translated_check.check_live(target)
    summary = translated_check.summarise(checks)
    FAILED = not summary["ok"]
    return {
        "ok": summary["ok"],
        "root": str(target),
        "total": summary["total"],
        "counts": summary["counts"],
        "contradicted": summary["contradicted"],
        "checks": [c.as_dict() for c in checks],
    }


if __name__ == "__main__":
    # The shared CLI only attaches tool flags (--root, --live) to the
    # subcommand parser, so a single-tool script needs the command name
    # inserted to make `qa_translated.py --json --live` work. Without this the
    # documented invocation failed with "invalid choice: '--root'".
    if len(sys.argv) > 1 and sys.argv[1] not in ("check", "-h", "--help", "check"):
        sys.argv.insert(1, "check")
    code = main(__file__, "Debug + hallucination + logic check for translated finders", REGISTRY)
    # main() returns 0 whenever the tool ran, whatever the tool found. The gate
    # has to be here, not inside the payload.
    raise SystemExit(code or (1 if FAILED else 0))
