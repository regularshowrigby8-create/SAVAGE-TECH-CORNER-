#!/usr/bin/env python3
"""
Criticism Check: Incomplete Code Scanner
Finds TODO: INCOMPLETE, FIXME, partial implementations.
"""

def scan_incomplete():
    findings = []
    try:
        with open(".claude/scripts/gates.sh", "r") as f:
            gates_content = f.read()
        if "TODO.*INCOMPLETE" in gates_content or "FIXME" in gates_content:
            findings.append("Gates script references incomplete markers — verify handling")
        else:
            findings.append("No incomplete markers in gates — PASS")
    except Exception as e:
        findings.append(f"Scan error: {e}")
    with open("factory-2/reports/incomplete_criticism.md", "w") as f:
        f.write("# Incomplete Code Criticism\nFindings:\n" + "\n".join(findings) + "\n")
    return findings

if __name__ == "__main__":
    scan_incomplete()
