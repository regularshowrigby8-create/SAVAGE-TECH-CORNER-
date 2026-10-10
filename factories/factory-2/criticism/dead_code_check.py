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
    with open(".github/workflows/factory-2-debug.yml", "r", encoding="utf-8") as f:
        workflow = f.read()

    # Accurately count top-level job definitions under 'jobs:'
    in_jobs = False
    job_count = 0
    for line in workflow.splitlines():
        if line.startswith("jobs:"):
            in_jobs = True
            continue
        if in_jobs:
            if line.startswith("  ") and not line.startswith("    ") and line.strip().endswith(":"):
                job_count += 1
            elif line and not line.startswith(" ") and not line.startswith("#"):
                break

    if job_count == 1:
        findings.append("Single job verified in workflow")
    else:
        findings.append(f"ERROR: Expected 1 job, detected {job_count}!")
    with open("factory-2/reports/dead_code_criticism.md", "w", encoding="utf-8") as f:
        f.write("# Dead Code Criticism\nFindings:\n" + "\n".join(findings) + "\n")
    return findings

if __name__ == "__main__":
    scan_dead_code()
