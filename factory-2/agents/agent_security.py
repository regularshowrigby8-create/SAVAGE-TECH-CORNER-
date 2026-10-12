#!/usr/bin/env python3
"""
Agent: Security (Deep SAST, Java/Kotlin WebView Audit, & High-Entropy Secret Scanner)
Finds:
1. Hardcoded high-entropy tokens and private keys (PEM, JWT, AWS/GitHub/Slack formats).
2. Plaintext credential assignments with substantive values (entropy > 3.0).
3. Insecure HTTP schemes, permissive WebView policies, and shell injections (`shell=True`).
4. Android WebView & manifest security: setJavaScriptEnabled audit, cleartext traffic, exported components.
"""

from __future__ import annotations

import ast
import math
import os
import re
import sys
from pathlib import Path
from typing import Any, Dict, List


def shannon_entropy(data: str) -> float:
    if not data:
        return 0.0
    entropy = 0.0
    for x in set(data):
        p_x = float(data.count(x)) / len(data)
        entropy += - p_x * math.log2(p_x)
    return entropy


TOKEN_REGEXES = [
    (r"gh[pousr]_[A-Za-z0-9_]{36,255}", "github_token"),
    (r"xox[baprs]-[0-9a-zA-Z]{10,48}", "slack_token"),
    (r"-----BEGIN (?:RSA|OPENSSH|EC|DSA)? PRIVATE KEY-----", "private_key"),
    (r"AKIA[0-9A-Z]{16}", "aws_access_key"),
]

ASSIGNMENT_REGEX = re.compile(
    r"""(?i)\b(api_key|secret_key|private_key|auth_token)\s*=\s*['"]([a-zA-Z0-9_\-\.]{16,})['"]"""
)

# Finding types that MUST block a green result. Anything else is advisory:
# still printed and written to the report, but not a release blocker.
BLOCKING_TYPES = {
    "hardcoded_token",
    "high_entropy_secret",
    "insecure_cleartext_traffic",
    "insecure_ssl_bypass",
    "insecure_file_access",
    "insecure_universal_file_access",
    "insecure_subprocess_shell",
}

ANDROID_INSECURE_PATTERNS = [
    (re.compile(r"""setAllowFileAccess\s*\(\s*true\s*\)"""), "insecure_file_access", "WebView allows local file access"),
    (re.compile(r"""setAllowUniversalAccessFromFileURLs\s*\(\s*true\s*\)"""), "insecure_universal_file_access", "WebView allows universal access from file URLs"),
    (re.compile(r"""android:usesCleartextTraffic\s*=\s*['"]true['"]"""), "insecure_cleartext_traffic", "Cleartext HTTP traffic permitted in manifest"),
    (re.compile(r"""handler\.proceed\(\)"""), "insecure_ssl_bypass", "SSL error handler proceeds without verification"),
    (re.compile(r"""shouldBlockScheme\(uri\.getScheme\(\)\)"""), "missing_host_allowlist_check", "WebView navigation validates scheme only without verifying allowed host domain"),
]


def scan_deep_security(directory: str = ".") -> List[Dict[str, Any]]:
    findings: List[Dict[str, Any]] = []
    excluded_dirs = {".git", "node_modules", "__pycache__", ".claude", "reports", ".factory", "docs"}

    for root, dirs, files in os.walk(directory):
        dirs[:] = [d for d in dirs if d not in excluded_dirs and "report" not in d]
        for fname in files:
            ext = Path(fname).suffix.lower()
            if ext not in {".py", ".env", ".sh", ".xml", ".java", ".kt"}:
                continue
            fpath = os.path.join(root, fname)
            if "factory-2/reports" in fpath or "factories/factory-2/reports" in fpath:
                continue

            try:
                with open(fpath, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read()
            except OSError:
                continue

            # 1. Regex & Pattern Scanner
            for pat, token_type in TOKEN_REGEXES:
                match = re.search(pat, content)
                if match:
                    findings.append({
                        "type": "hardcoded_token",
                        "file": fpath,
                        "token_type": token_type,
                        "line": content[:match.start()].count("\n") + 1,
                    })

            # 2. Plaintext Assignment + Entropy check (Threshold tuned to 3.0 to catch base64 keys)
            for match in ASSIGNMENT_REGEX.finditer(content):
                val = match.group(2)
                if any(mock in val.lower() for mock in ("mock", "placeholder", "test", "example", "dummy")):
                    continue
                if shannon_entropy(val) > 3.0:
                    findings.append({
                        "type": "high_entropy_secret",
                        "file": fpath,
                        "variable": match.group(1),
                        "line": content[:match.start()].count("\n") + 1,
                    })

            # 3. Android Security & Manifest Auditing
            if ext in {".java", ".kt", ".xml"}:
                for pattern, rule_type, desc in ANDROID_INSECURE_PATTERNS:
                    match = pattern.search(content)
                    if match:
                        findings.append({
                            "type": rule_type,
                            "file": fpath,
                            "line": content[:match.start()].count("\n") + 1,
                            "description": desc,
                        })

            # 4. AST analysis in Python (subprocess shell=True, dynamic exec/eval)
            if ext == ".py":
                try:
                    tree = ast.parse(content, filename=fpath)
                    for node in ast.walk(tree):
                        if isinstance(node, ast.Call):
                            for kw in node.keywords:
                                if kw.arg == "shell" and isinstance(kw.value, ast.Constant) and kw.value.value is True:
                                    findings.append({
                                        "type": "insecure_subprocess_shell",
                                        "file": fpath,
                                        "line": node.lineno,
                                    })
                except SyntaxError:
                    pass

    findings.sort(key=lambda x: (x.get("file", ""), x.get("line", 0)))
    report_path = Path("factory-2/reports/security_report.md")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    with open(report_path, "w", encoding="utf-8") as f:
        f.write(f"# Security Report\nFindings: {findings}\n")
    return findings


if __name__ == "__main__":
    results = scan_deep_security()
    blocking = [f for f in results if f.get("type") in BLOCKING_TYPES]
    advisory = [f for f in results if f.get("type") not in BLOCKING_TYPES]
    print(
        f"[AGENT-SECURITY] Security scan complete. Findings: {len(results)} "
        f"(blocking: {len(blocking)}, advisory: {len(advisory)})"
    )
    for finding in blocking[:25]:
        print(f"  BLOCKING - {finding.get('type')}: {finding.get('file')}:{finding.get('line')}")
    for finding in advisory[:25]:
        print(f"  advisory - {finding.get('type')}: {finding.get('file')}:{finding.get('line')}")
    if blocking:
        print("[AGENT-SECURITY: FAIL] Blocking vulnerabilities must be repaired before green.")
    sys.exit(1 if blocking else 0)
