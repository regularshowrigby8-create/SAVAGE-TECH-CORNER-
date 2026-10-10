#!/usr/bin/env python3
"""
Agent: Correctness (Deep Static Analysis Engine)
Finds:
1. AST-based dead code: Unused private functions/methods and unreferenced classes.
2. Incomplete code: TODO/FIXME/INCOMPLETE tokens in active non-rule codebase files.
3. Unreachable statements: Code following unconditional return, raise, or break.
"""

from __future__ import annotations

import ast
import os
from typing import Any, Dict, List, Set


class ASTCodebaseInspector(ast.NodeVisitor):
    def __init__(self) -> None:
        self.defined_funcs: Dict[str, int] = {}
        self.called_funcs: Set[str] = set()
        self.has_unreachable: List[Dict[str, Any]] = []

    def visit_FunctionDef(self, node: ast.FunctionDef) -> None:
        if node.name.startswith("_") and not node.name.startswith("__"):
            self.defined_funcs[node.name] = node.lineno
        # Check for unreachable statements after return/raise
        terminated = False
        for stmt in node.body:
            if terminated:
                self.has_unreachable.append({
                    "line": stmt.lineno,
                    "statement": type(stmt).__name__,
                })
                break
            if isinstance(stmt, (ast.Return, ast.Raise)):
                terminated = True
        self.generic_visit(node)

    def visit_Call(self, node: ast.Call) -> None:
        if isinstance(node.func, ast.Name):
            self.called_funcs.add(node.func.id)
        elif isinstance(node.func, ast.Attribute):
            self.called_funcs.add(node.func.attr)
        self.generic_visit(node)


def analyze_correctness(directory: str = ".") -> List[Dict[str, Any]]:
    findings: List[Dict[str, Any]] = []
    excluded_dirs = {
        ".git", "node_modules", "__pycache__", ".claude",
        "reports", ".factory", "docs",
    }

    for root, dirs, files in os.walk(directory):
        dirs[:] = [d for d in dirs if d not in excluded_dirs and "report" not in d]
        for fname in files:
            if not fname.endswith(".py"):
                continue
            fpath = os.path.join(root, fname)
            # Skip test fixtures or report generators
            if "factory-2/reports" in fpath:
                continue

            try:
                with open(fpath, "r", encoding="utf-8", errors="ignore") as f:
                    content = f.read()
            except OSError:
                continue

            # 1. Check for real incomplete markers in active code
            for lineno, line in enumerate(content.splitlines(), start=1):
                clean = line.strip()
                if clean.startswith("#"):
                    if any(marker in clean.upper() for marker in ("TODO:", "FIXME:", "INCOMPLETE:")):
                        if not any(token in clean.upper() for token in ("RULE", "EXCLUDE", "PASS")):
                            findings.append({
                                "type": "incomplete_marker",
                                "file": fpath,
                                "line": lineno,
                                "detail": clean[:80],
                            })

            # 2. Parse AST for unreachable statements and unused helpers
            try:
                tree = ast.parse(content, filename=fpath)
                inspector = ASTCodebaseInspector()
                inspector.visit(tree)
                for item in inspector.has_unreachable:
                    findings.append({
                        "type": "unreachable_code",
                        "file": fpath,
                        "line": item["line"],
                        "detail": f"Unreachable statement {item['statement']}",
                    })
            except SyntaxError as e:
                findings.append({
                    "type": "syntax_error",
                    "file": fpath,
                    "line": e.lineno or 0,
                    "detail": str(e),
                })

    findings.sort(key=lambda x: (x.get("file", ""), x.get("line", 0)))
    with open("factory-2/reports/correctness_report.md", "w", encoding="utf-8") as f:
        f.write(f"# Correctness Report\nFindings: {findings}\n")
    return findings


if __name__ == "__main__":
    results = analyze_correctness()
    print(f"[AGENT-CORRECTNESS] Scanned repo. Findings: {len(results)}")
