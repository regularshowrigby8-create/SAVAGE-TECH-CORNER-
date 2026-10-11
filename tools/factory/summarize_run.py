"""Create a fail-closed summary for a hosted quality run."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

REQUIRED = {"policy-and-source", "android-host", "device-and-evidence", "required-quality"}


def classify(result: str | None) -> str:
    if result == "success":
        return "EXECUTED_PASS"
    if result in {"failure", "cancelled", "timed_out"}:
        return "EXECUTED_FAIL"
    if result == "skipped":
        return "SKIPPED_POLICY"
    return "RUNNING"


def summarize(needs: dict) -> dict:
    jobs = {
        name: {"result": data.get("result"), "status": classify(data.get("result"))}
        for name, data in needs.items()
    }
    missing = sorted(REQUIRED - set(jobs))
    failed = sorted(name for name, data in jobs.items() if data["status"] != "EXECUTED_PASS")
    approved = not missing and not failed
    return {
        "schema_version": 1,
        "status": "PASS" if approved else ("INCOMPLETE" if missing else "BLOCKED_OR_FINDINGS"),
        "jobs": jobs,
        "missing_required_jobs": missing,
        "failed_jobs": failed,
        "release_approved": approved,
        "apk_link_allowed": approved,
        "instructions": [
            "Do not publish an APK unless release_approved is true.",
            "Do not convert missing artifacts into PASS.",
            "Distinguish blocked jobs from executed findings.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--needs", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = summarize(json.loads(args.needs))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
