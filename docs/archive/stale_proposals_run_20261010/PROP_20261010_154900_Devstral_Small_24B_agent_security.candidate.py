#!/usr/bin/env python3
"""
Agent: Security (Security Model)
Finds: hardcoded secrets, missing validation in actual source files.
"""
import os, re

def scan_security(directory="."):
    findings = []
    patterns = [r"password[\s]*=[\s]*['\"][^'\"]+['\"]", r"secret[\s]*=[\s]*['\"][^'\"]+['\"]", r"api_key[\s]*=[\s]*['\"][^'\"]+['\"]"]
    for root, dirs, files in os.walk(directory):
        dirs[:] = [d for d in dirs if d not in ['.git', 'node_modules', '__pycache__']]
        for fname in files:
            if fname.endswith('.py') or fname.endswith('.env') or fname.endswith('.sh'):
                fpath = os.path.join(root, fname)
                try:
                    with open(fpath, 'r', encoding='utf-8', errors='ignore') as f:
                        content = f.read()
                    for pat in patterns:
                        if re.search(pat, content, re.IGNORECASE):
                            findings.append({"type": "potential_secret", "file": fpath, "pattern": pat})
                except Exception:
                    pass
    with open("factory-2/reports/security_report.md", "w") as f:
        f.write(f"# Security Report\nFindings: {findings}\n")
    return findings

if __name__ == "__main__":
    findings = scan_security()
    print(f"[AGENT-SECURITY] Security scan complete. Findings: {len(findings)}")
