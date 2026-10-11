"""Turn factory findings and blockers into bounded, non-mutating next actions."""

import json
import sys
from pathlib import Path

RULE_ACTIONS = {
    "APP-CLEAR-001": ("security-owner", "Review manifest cleartext compatibility and either constrain it or document the exact HTTP exception with expiry."),
    "APP-CLEAR-002": ("security-owner", "Review network security config cleartext scope; preserve only explicitly approved local/legacy transport."),
    "APP-SECRET-001": ("security-owner", "Classify the candidate without exposing its value; remove, externalize or document public protocol provenance."),
    "WEBVIEW-MIXED-001": ("webview-owner", "Set mixed content to NEVER_ALLOW and add a regression for HTTPS pages with HTTP subresources."),
    "WEBVIEW-DEBUG-001": ("android-owner", "Gate WebView debugging on BuildConfig.DEBUG and verify release manifest behavior."),
    "WEBVIEW-TLS-001": ("webview-owner", "Cancel TLS errors before visible navigation and test invalid, expired and mismatched certificates."),
    "WEBVIEW-FILE-001": ("webview-owner", "Make file URL cross-origin access explicit and default-off; add a negative access test."),
    "WEBVIEW-FILE-002": ("webview-owner", "Make universal file URL access explicit and default-off; add a negative access test."),
}


def plan(report: dict, summary: dict | None = None) -> dict:
    """Create one accountable action per finding and one setup action per blocker."""
    actions = []
    for item in report.get("findings", []):
        owner, action = RULE_ACTIONS.get(item["rule"], ("issue-owner", "Reproduce the finding and select one minimal verified fix."))
        actions.append({"id": f"{item['rule']}:{item['path']}:{item['line']}", "owner": owner, "rule": item["rule"], "path": item["path"], "line": item["line"], "action": action, "status": "OPEN", "auto_fix": False})
    if summary:
        for result in summary.get("results", []):
            if result.get("status") == "BLOCKED":
                actions.append({"id": f"provision:{result['id']}", "owner": "factory", "rule": "FACTORY-PREREQUISITE", "path": result["id"], "line": 0, "action": "Provision the declared locked environment or record the exact prerequisite blocker; rerun before issue closure.", "status": "BLOCKED", "auto_fix": False})
    return {"schema_version": 1, "actions": actions, "open_count": sum(action["status"] != "CLOSED" for action in actions), "auto_fix_count": 0, "release_approved": False, "semantic_closure": False}


def main() -> int:
    """Read a policy report and optionally a factory summary, then write an action plan."""
    if len(sys.argv) not in {2, 3}:
        raise SystemExit("usage: remediation_plan.py POLICY_JSON [SUMMARY_JSON]")
    report = json.loads(Path(sys.argv[1]).read_text())
    summary = json.loads(Path(sys.argv[2]).read_text()) if len(sys.argv) == 3 else None
    result = plan(report, summary)
    output = Path(sys.argv[1]).with_name("remediation-plan.json")
    output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"actions": len(result["actions"]), "open": result["open_count"], "output": str(output)}))
    return 1 if result["open_count"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
