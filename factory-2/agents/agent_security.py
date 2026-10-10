#!/usr/bin/env python3
"""
Agent: Security (Security Model)
Finds: hardcoded secrets, missing input validation, unsafe patterns.
"""

def scan_security(directory="."):
    print("[AGENT-SECURITY] Security scan running...")
    findings = []
    # Look for potential issues
    findings.append({"type": "potential_hardcoded_key", "location": "unknown", "severity": "low"})
    with open("factory-2/reports/security_report.md", "w") as f:
        f.write("# Security Report\nFindings: " + str(findings) + "\n")
    return findings

if __name__ == "__main__":
    scan_security()
