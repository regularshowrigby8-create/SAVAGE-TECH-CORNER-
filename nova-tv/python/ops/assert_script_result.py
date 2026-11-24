#!/usr/bin/env python3
"""Fail a script smoke test on a false-green wrapper or nested failure."""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.result import payload_is_ok, result_failures  # noqa: E402


def main() -> int:
    label = sys.argv[1] if len(sys.argv) > 1 else "script"
    try:
        payload = json.load(sys.stdin)
    except Exception as error:
        print(f"STRICT RESULT FAIL {label}: stdout is not one JSON object: {error}")
        return 1
    if not isinstance(payload, dict):
        print(f"STRICT RESULT FAIL {label}: JSON root is not an object")
        return 1
    if not payload_is_ok(payload):
        reasons = []
        if payload.get("ok") is not True:
            reasons.append("top-level ok is not true")
        if "result" not in payload:
            reasons.append("result is missing")
        else:
            reasons.extend(result_failures(payload["result"]))
        print(f"STRICT RESULT FAIL {label}: {'; '.join(reasons) or 'no explicit success evidence'}")
        return 1
    print(f"STRICT RESULT PASS {label}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
