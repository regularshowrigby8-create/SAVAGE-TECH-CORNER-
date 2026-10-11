"""Explicit, bounded repair handlers for formatting and policy metadata.

These handlers are deliberately separate from read-only factory engines. They
never run during a quality gate implicitly and never publish or release source.
Formatting is the only automatic source mutation permitted here.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

# Direct execution from repository root must work as well as module execution.
if __package__ in {None, ""}:
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from tools.factory.factory import ROOT

TRAILER = "Searchhh-Quality-Policy: accepted-v1"
GRADLE = "./gradlew"


def run(command: list[str], timeout: int = 1800) -> int:
    print("$ " + " ".join(command))
    return subprocess.run(command, cwd=ROOT, timeout=timeout, check=False).returncode


def policy(report: Path) -> int:
    message = subprocess.check_output(["git", "log", "-1", "--format=%B"], cwd=ROOT, text=True)
    ok = TRAILER in message.splitlines()
    result = {"handler": "policy", "state": "VERIFIED" if ok else "REPAIR_REQUIRED",
              "release_approved": False, "trailer": TRAILER}
    if not ok:
        result["action"] = "Create a new commit with the exact trailer; history is not rewritten by this handler."
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    return 0 if ok else 2


def gradle(task: str, apply: bool, report: Path) -> int:
    if task == "ktlint":
        command = [GRADLE, "ktlintFormat" if apply else "ktlintCheck", "--no-daemon", "--max-workers=2"]
    elif task == "detekt":
        command = [GRADLE, "detekt", "--no-daemon", "--max-workers=2"]
        if apply:
            command.insert(2, "--auto-correct")
    else:
        raise ValueError("unknown repair handler")
    code = run(command)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({"handler": task, "mode": "apply" if apply else "check",
                                  "exit_code": code, "release_approved": False}, indent=2) + "\n")
    return code


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("handler", choices=["ktlint", "detekt", "policy"])
    parser.add_argument("--apply", action="store_true", help="Explicitly permit formatting/autocorrect")
    parser.add_argument("--report", type=Path, default=ROOT / "build/reports/factory/repair-handler.json")
    args = parser.parse_args()
    if args.handler == "policy":
        if args.apply:
            parser.error("policy history is never rewritten; add a compliant follow-up commit")
        return policy(args.report)
    if args.apply and args.handler != "ktlint":
        parser.error("Detekt is report-only; semantic auto-correction is prohibited")
    if args.apply and os.environ.get("SEARCHHH_ALLOW_SOURCE_FORMATTING") != "1":
        parser.error("source mutation requires SEARCHHH_ALLOW_SOURCE_FORMATTING=1")
    return gradle(args.handler, args.apply, args.report)


if __name__ == "__main__":
    sys.exit(main())
