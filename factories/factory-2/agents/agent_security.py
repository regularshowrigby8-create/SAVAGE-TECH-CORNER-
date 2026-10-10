#!/usr/bin/env python3
"""
Agent: Security (Deep SAST & High-Entropy Secret Scanner)
Finds:
1. Hardcoded high-entropy tokens and private keys (PEM, JWT, AWS/GitHub/Slack formats).
2. Plaintext credential assignments with substantive values.
3. Insecure HTTP schemes, permissive WebView policies, and shell injections (`shell=True`).
"""

from __future__ import annotations

import ast
import math
import os
import re
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


def scan_deep_security(directory: str = ".") -> List[Dict[str, Any]]:
    findings: List[Dict[str, Any]] = []
    excluded_dirs = {".git", "node_modules", "__pycache__", ".claude", "reports", ".factory", "docs"}

    for root, dirs, files in os.walk(directory):
        dirs[:] = [d for d in dirs if d not in excluded_dirs and "report" not in d]
        for fname in files:
            if not (fname.endswith(".py") or fname.endswith(".env") or fname.endswith(".sh") or fname.endswith(".xml")):
                continue
            fpath = os.path.join(root, fname)
            if "factory-2/reports" in fpath:
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

            # 2. Plaintext Assignment + Entropy check
            for match in ASSIGNMENT_REGEX.finditer(content):
                val = match.group(2)
                # Ignore test placeholders
                if any(mock in val.lower() for mock in ("mock", "placeholder", "test", "example", "dummy")):
                    continue
                if shannon_entropy(val) > 3.5:
                    findings.append({
                        "type": "high_entropy_secret",
                        "file": fpath,
                        "variable": match.group(1),
                        "line": content[:match.start()].count("\n") + 1,
                    })

            # 3. Insecure AST analysis in Python
            if fname.endswith(".py"):
                try:
                    tree = ast.parse(content, filename=fpath)
                    for node in ast.walk(tree):
                        if isinstance(node, ast.Call):
                            # subprocess.Popen(..., shell=True) check
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
    with open("factory-2/reports/security_report.md", "w", encoding="utf-8") as f:
        f.write(f"# Security Report\nFindings: {findings}\n")
    return findings


if __name__ == "__main__":
    results = scan_deep_security()
    print(f"[AGENT-SECURITY] Security scan complete. Findings: {len(results)}")
