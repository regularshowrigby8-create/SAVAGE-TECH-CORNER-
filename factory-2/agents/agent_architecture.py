#!/usr/bin/env python3
"""Factory 2 architecture checks with explicit cross-branch references excluded."""
import os


def review_architecture(directory="."):
    findings = []
    for root, dirs, files in os.walk(directory):
        dirs[:] = [d for d in dirs if d not in {".git", "node_modules", "__pycache__", ".claude"}]
        for fname in files:
            fpath = os.path.join(root, fname)
            if "factory-2/reports" in fpath or ".factory/logs" in fpath or not fname.endswith(".md"):
                continue
            try:
                content = open(fpath, encoding="utf-8", errors="ignore").read()
            except OSError:
                continue
            for line in content.splitlines():
                if "workspace/code/debug_pipeline.py" not in line and "workshop/code" not in line:
                    continue
                if any(marker in line.upper() for marker in ("FACTORY-1", "BRANCH ONLY", "NOT THIS BRANCH", "LIVES ON")):
                    continue
                findings.append({"type": "missing_reference", "reference": line.strip(), "expected_file": "workspace/code/debug_pipeline.py"})
    findings.sort(key=lambda finding: finding.get("expected_file", finding.get("reference", "")))
    with open("factory-2/reports/architecture_report.md", "w", encoding="utf-8") as f:
        f.write(f"# Architecture Report\nFindings: {findings}\n")
    return findings


if __name__ == "__main__":
    findings = review_architecture()
    print(f"[AGENT-ARCHITECTURE] Architecture review complete. Findings: {len(findings)}")
    raise SystemExit(1 if findings else 0)
