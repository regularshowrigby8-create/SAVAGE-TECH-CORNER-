"""Bounded supervisor for slow issue checks: provision, retry, summarize, stop."""

import argparse
import json
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from tools.factory import factory
from tools.factory.issue_workflow import ISSUE_PROFILES

ROOT = factory.ROOT
MAX_ATTEMPTS = 2
MAX_PROVISION_SECONDS = 900


def selected_tools(category: str) -> list[dict]:
    """Resolve the issue category against the reviewed registry."""
    ids = ISSUE_PROFILES[category]
    tools = {tool["id"]: tool for tool in factory.load_tools()}
    missing = sorted(ids - tools.keys())
    if missing:
        raise ValueError("Registry missing: " + ",".join(missing))
    return [tools[tool_id] for tool_id in sorted(ids)]


def ecosystems(tools: list[dict]) -> list[str]:
    """Return only ecosystems needed by the selected issue, in stable order."""
    mapping = {"python": "python", "npm": "node", "github-binary": "binary"}
    return sorted({mapping[tool["install"]["type"]] for tool in tools if tool["install"]["type"] in mapping})


def provision(ecosystem: str, log: Path) -> dict:
    """Provision one locked ecosystem with a hard supervisor timeout."""
    started = time.monotonic()
    command = [sys.executable, "tools/factory/install.py", "--ecosystem", ecosystem, "--workers", "3"]
    try:
        completed = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=MAX_PROVISION_SECONDS, check=False)
        log.write_text(completed.stdout + completed.stderr)
        return {"ecosystem": ecosystem, "exit": completed.returncode, "seconds": round(time.monotonic() - started, 2), "log": str(log.relative_to(ROOT))}
    except subprocess.TimeoutExpired as error:
        log.write_text((error.stdout or "") + (error.stderr or "") + "\nSupervisor timeout\n")
        return {"ecosystem": ecosystem, "exit": None, "seconds": MAX_PROVISION_SECONDS, "log": str(log.relative_to(ROOT)), "status": "TIMEOUT"}


def run(category: str, issue: str, provision_missing: bool) -> int:
    """Run selected tools, automatically retry blocked families once, then stop."""
    tools = selected_tools(category)
    output = ROOT / "build/reports/factory/auto-issues" / issue
    output.mkdir(parents=True, exist_ok=True)
    record = {"schema_version": 1, "issue": issue, "category": category, "started": datetime.now(timezone.utc).isoformat(), "max_attempts": MAX_ATTEMPTS, "release_approved": False, "provisioning": [], "attempts": []}
    for attempt in range(1, MAX_ATTEMPTS + 1):
        args = argparse.Namespace(workers=1 if any("android" in tool["profiles"] for tool in tools) else 3, apk=None)
        exit_code = factory.run_batch(tools, args)
        summaries = sorted((ROOT / "build/reports/factory").glob("*/summary.json"), key=lambda path: path.stat().st_mtime)
        summary = json.loads(summaries[-1].read_text()) if summaries else {"results": []}
        record["attempts"].append({"number": attempt, "exit": exit_code, "summary": str(summaries[-1].relative_to(ROOT)) if summaries else None, "counts": {status: sum(item.get("status") == status for item in summary["results"]) for status in ("PASS", "FAIL", "BLOCKED", "REPORTED")}})
        if summaries:
            from tools.factory.remediation_plan import plan as make_plan
            policy_path = next((Path(item["log"]).parent / "app-security.json" for item in summary["results"] if item["id"] == "app-security-policy" and item.get("log")), None)
            if policy_path and policy_path.is_file():
                remediation = make_plan(json.loads(policy_path.read_text()), summary)
                remediation_path = output / f"remediation-plan-attempt-{attempt}.json"
                remediation_path.write_text(json.dumps(remediation, indent=2) + "\n")
                record.setdefault("remediation_plans", []).append(str(remediation_path.relative_to(ROOT)))
        blocked_types = {item["install"]["type"] for item, result in zip(tools, summary["results"]) if result.get("status") == "BLOCKED"}
        if not provision_missing or not blocked_types or attempt == MAX_ATTEMPTS:
            break
        needed = sorted({{"python": "python", "npm": "node", "github-binary": "binary"}[kind] for kind in blocked_types if kind in {"python", "npm", "github-binary"}})
        for ecosystem in needed:
            record["provisioning"].append(provision(ecosystem, output / f"provision-{ecosystem}-attempt-{attempt}.log"))
        provision_missing = False
    record["finished"] = datetime.now(timezone.utc).isoformat()
    record["status"] = "READY_FOR_REVIEW" if record["attempts"] and record["attempts"][-1]["exit"] == 0 else "BLOCKED_OR_FINDINGS"
    output.joinpath("supervisor.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps(record, indent=2))
    return 0 if record["status"] == "READY_FOR_REVIEW" else 1


def main() -> int:
    """Expose a bounded automatic issue command."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("issue")
    parser.add_argument("--category", choices=sorted(ISSUE_PROFILES), required=True)
    parser.add_argument("--no-provision", action="store_true")
    args = parser.parse_args()
    return run(args.category, args.issue, not args.no_provision)


if __name__ == "__main__":
    raise SystemExit(main())
