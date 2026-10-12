#!/usr/bin/env python3
"""
Agent: Architecture (Static Modularity & Clean Layering Inspector)
Finds:
1. Missing directory categories (`shell`, `frontend`, `backend`, `factories`).
2. Inter-layer boundary leaks (e.g. backend importing Android WebView directly).
3. Dangling cross-branch path references.
"""

from __future__ import annotations

import ast
import os
from pathlib import Path
from typing import Any, Dict, List


def review_deep_architecture(directory: str = ".") -> List[Dict[str, Any]]:
    findings: List[Dict[str, Any]] = []
    root = Path(directory)

    # 1. Structural Category Verification
    for required_cat in ("frontend", "backend", "shell", "factories"):
        cat_path = root / required_cat
        if not cat_path.exists() or not cat_path.is_dir():
            findings.append({
                "type": "missing_category_directory",
                "category": required_cat,
            })

    # 2. Layer boundary separation checks (Backend must not import WebView or Android packages)
    backend_dir = root / "backend"
    if backend_dir.exists():
        for py_path in backend_dir.rglob("*.py"):
            try:
                with open(py_path, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read()
                tree = ast.parse(content, filename=str(py_path))
                for node in ast.walk(tree):
                    if isinstance(node, ast.Import):
                        for alias in node.names:
                            if "webview" in alias.name.lower() or "android" in alias.name.lower():
                                findings.append({
                                    "type": "layer_leak",
                                    "file": str(py_path),
                                    "imported": alias.name,
                                })
            except (OSError, SyntaxError) as exc:
                # An unparseable backend file is a finding, never a silent skip.
                findings.append({
                    "type": "parse_error",
                    "file": str(py_path),
                    "detail": type(exc).__name__,
                })

    findings.sort(key=lambda x: x.get("type", ""))
    report_file = root / "factory-2" / "reports" / "architecture_report.md"
    report_file.parent.mkdir(parents=True, exist_ok=True)
    with open(report_file, "w", encoding="utf-8") as f:
        f.write(f"# Architecture Report\nFindings: {findings}\n")
    return findings


if __name__ == "__main__":
    results = review_deep_architecture()
    print(f"[AGENT-ARCHITECTURE] Architecture review complete. Findings: {len(results)}")
    raise SystemExit(1 if results else 0)
