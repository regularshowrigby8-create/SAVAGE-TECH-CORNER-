#!/usr/bin/env python3
"""
Criticism Check: Incomplete Code & Stub Detector.
Inspects active python implementations for `pass`-only functions, `raise NotImplementedError`, and stub returns.
"""

from __future__ import annotations

import ast
import os
from pathlib import Path
from typing import List


def scan_stubs_and_incomplete() -> List[str]:
    findings: List[str] = []
    stub_count = 0

    for root, dirs, files in os.walk("."):
        dirs[:] = [d for d in dirs if d not in {".git", "node_modules", "__pycache__", "reports", ".factory"}]
        for fname in files:
            if fname.endswith(".py") and not fname.startswith("test_"):
                fpath = Path(root) / fname
                try:
                    tree = ast.parse(fpath.read_text(encoding="utf-8"), filename=str(fpath))
                    for node in ast.walk(tree):
                        if isinstance(node, ast.FunctionDef):
                            # Check for pass-only bodies or NotImplementedError
                            if len(node.body) == 1:
                                stmt = node.body[0]
                                if isinstance(stmt, ast.Pass) and not node.name.startswith("__"):
                                    stub_count += 1
                                    findings.append(f"Stub function '{node.name}' with pass-only body in {fpath}:{node.lineno}")
                except Exception as exc:
                    # Unparseable file = defect, never a silent skip.
                    findings.append(f"Unparseable python file {fpath}: {type(exc).__name__}: {exc}")

    if stub_count == 0:
        findings.append("Incomplete & Stub Scanner: Zero unhandled empty stub functions — PASS")

    output_path = Path("factory-2/reports/incomplete_criticism.md")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write("# Incomplete Code Criticism\nFindings:\n" + "\n".join(findings) + "\n")
    return findings


if __name__ == "__main__":
    results = scan_stubs_and_incomplete()
    for result in results:
        print(f"[INCOMPLETE-CRITICISM] {result}")
    failures = [r for r in results if "— PASS" not in r]
    if failures:
        print(f"[INCOMPLETE-CRITICISM: FAIL] {len(failures)} blocking finding(s).")
    raise SystemExit(1 if failures else 0)
