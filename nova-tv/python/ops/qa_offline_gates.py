#!/usr/bin/env python3
"""Run the offline Nova gates with explicit nested-result assertions.

This replaces the old workflow loop that grep'ed the top-level `"ok": true`
wrapper. Each tool must exit zero, print exactly one JSON object, and pass
`nova.result.payload_is_ok`. No tool is skipped as a success and no optional
network/API-key tool is silently included.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

PYTHON_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PYTHON_DIR))

from nova.result import payload_is_ok, result_failures  # noqa: E402

GATES = [
    ("qa_compile", ["scripts/qa_compile.py", "--json"], 60),
    ("qa_import_check", ["scripts/qa_import_check.py", "--json"], 60),
    ("qa_secrets", ["scripts/qa_secrets.py", "--json"], 60),
    ("qa_todos", ["scripts/qa_todos.py", "--json"], 60),
    ("qa_tests", ["scripts/qa_tests.py", "--json"], 180),
    ("qa_translated", ["scripts/qa_translated.py", "--json", "--root", ".."], 90),
    ("verify_claims", ["scripts/verify_claims.py", "--json"], 90),
]


def main() -> int:
    records = []
    errors = []
    for name, argv, timeout in GATES:
        record = {"gate": name, "argv": argv, "ok": False}
        try:
            completed = subprocess.run(
                [sys.executable, *argv],
                cwd=str(PYTHON_DIR),
                capture_output=True,
                text=True,
                timeout=timeout,
                check=False,
            )
            record["exit"] = completed.returncode
            if completed.returncode != 0:
                errors.append(f"{name}: exit {completed.returncode}")
                record["stderr_tail"] = completed.stderr[-1000:]
                records.append(record)
                continue
            payload = json.loads(completed.stdout)
            if not isinstance(payload, dict):
                errors.append(f"{name}: JSON root is not an object")
                records.append(record)
                continue
            if not payload_is_ok(payload):
                reasons = result_failures(payload.get("result"))
                errors.append(f"{name}: {'; '.join(reasons) or 'no nested success evidence'}")
                records.append(record)
                continue
            record["ok"] = True
            records.append(record)
        except subprocess.TimeoutExpired:
            errors.append(f"{name}: timed out after {timeout}s")
            records.append(record)
        except json.JSONDecodeError as error:
            errors.append(f"{name}: stdout is not one JSON object: {error}")
            records.append(record)
        except Exception as error:  # explicit visibility, never a green skip
            errors.append(f"{name}: {type(error).__name__}: {error}")
            records.append(record)

    ok = not errors and len(records) == len(GATES) and all(item["ok"] for item in records)
    result = {
        "ok": ok,
        "total": len(GATES),
        "passed": sum(1 for item in records if item["ok"]),
        "failed": len(GATES) - sum(1 for item in records if item["ok"]),
        "errors": errors,
        "records": records,
    }
    print(json.dumps({"ok": ok, "tool": "qa_offline_gates", "result": result}, indent=2, default=str))
    if ok:
        print("STRICT OFFLINE GATES PASS", file=sys.stderr)
    else:
        for error in errors:
            print(f"STRICT OFFLINE GATE FAIL: {error}", file=sys.stderr)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
