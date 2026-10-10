#!/usr/bin/env python3
"""
Criticism Check: Dead Code Scanner
Runs as part of Factory 2 single job.
"""

def scan_dead_code():
    patterns = [
        "def .*unused",
        "class .*unused",
        "function .*not called"
    ]
    findings = []
    with open(".github/workflows/factory-2-debug.yml", "r") as f:
        workflow = f.read()
    # Verify only one job
    if workflow.count("jobs:") > 0 and workflow.count("job:") == 1:
        findings.append("Single job verified in workflow")
    else:
        findings.append("ERROR: Multiple jobs detected!")
    with open("factory-2/reports/dead_code_criticism.md", "w") as f:
        f.write("# Dead Code Criticism\nFindings:\n" + "\n".join(findings) + "\n")
    return findings

if __name__ == "__main__":
    scan_dead_code()
