"""Run one issue's repeatable audit, evidence routing and fail-closed closure."""

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from tools.factory import automation, factory

ROOT = factory.ROOT

ISSUE_PROFILES = {
    "security": {"app-security-policy", "bandit", "semgrep", "gitleaks", "detect-secrets", "trivy", "osv-scanner"},
    "webview": {"app-security-policy", "ast-grep", "eslint", "jest", "pytest"},
    "api": {"openapi-spec-validator", "pipdeptree", "pytest", "bandit", "mypy", "pyright"},
    "ui": {"playwright", "axe-core", "html-validate", "stylelint", "jest"},
    "dependency": {"npm-audit", "pipdeptree", "pip-audit", "cyclonedx-py", "syft", "trivy"},
}


def run(issue: str, category: str, verify: bool) -> int:
    """Execute scoped tools and write an issue packet; closure requires clean verification."""
    if not issue or any(char in issue for char in "/\\\x00"):
        raise ValueError("Issue identifier must be a nonempty safe name")
    ids = ISSUE_PROFILES[category]
    available = {tool["id"]: tool for tool in factory.load_tools()}
    missing = sorted(ids - available.keys())
    if missing:
        raise ValueError("Registry missing issue tools: " + ",".join(missing))
    selected = [available[tool_id] for tool_id in sorted(ids)]
    result_dir = ROOT / "build/reports/factory/issues" / issue
    result_dir.mkdir(parents=True, exist_ok=True)
    plan = {"issue": issue, "category": category, "tools": sorted(ids), "verify_requested": verify, "release_approved": False}
    (result_dir / "plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    args = argparse.Namespace(workers=1 if any("android" in tool["profiles"] for tool in selected) else 3, apk=None)
    status = factory.run_batch(selected, args)
    summary_paths = sorted(result_dir.parent.parent.glob("*/summary.json"))
    summary = summary_paths[-1] if summary_paths else None
    packet = {"issue": issue, "category": category, "created": datetime.now(timezone.utc).isoformat(), "status": "VERIFYING", "tools": sorted(ids), "runner_exit": status, "summary": str(summary) if summary else None, "closure": "Not closed: audit/reproduce/fix/verification is incomplete", "release_approved": False}
    if verify and status == 0:
        packet["status"] = "VERIFIED_PENDING_REVIEW"
        packet["closure"] = "Checks passed for this scoped run; human root-cause and diff review still required"
    (result_dir / "issue.json").write_text(json.dumps(packet, indent=2) + "\n")
    print(json.dumps(packet, indent=2))
    return 0 if packet["status"] == "VERIFIED_PENDING_REVIEW" else 1


def main() -> int:
    """Provide one bounded issue command instead of ad-hoc whole-repo repetition."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("issue")
    parser.add_argument("--category", choices=sorted(ISSUE_PROFILES), required=True)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    return run(args.issue, args.category, args.verify)


if __name__ == "__main__":
    sys.exit(main())
