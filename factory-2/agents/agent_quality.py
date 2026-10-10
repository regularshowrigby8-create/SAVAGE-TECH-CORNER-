#!/usr/bin/env python3
"""Factory 2 quality checks for files that belong to this branch."""
import os


def check_quality(directory="."):
    findings = []
    required = [
        "factory-2/scripts/run_all.sh",
        "factory-2/agents/agent_critic.py",
        "factory-2/manager/guidance.py",
    ]
    missing = [path for path in required if not os.path.exists(path)]
    if missing:
        findings.append({"type": "missing_factory2_files", "files": missing})
    else:
        findings.append({"type": "factory2_pipeline_present", "target": "factory-2/scripts/run_all.sh"})
    findings.append({"type": "branch_scoped", "note": "Factory 1 workspace pipeline is intentionally not required on factory-2"})
    with open("factory-2/reports/quality_report.md", "w", encoding="utf-8") as f:
        f.write(f"# Quality Report\nFindings: {findings}\n")
    return findings


if __name__ == "__main__":
    findings = check_quality()
    print(f"[AGENT-QUALITY] Quality check complete. Findings: {len(findings)}")
    raise SystemExit(1 if any(f["type"] == "missing_factory2_files" for f in findings) else 0)
