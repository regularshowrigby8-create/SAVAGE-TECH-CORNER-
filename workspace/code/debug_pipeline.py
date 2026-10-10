#!/usr/bin/env python3
"""Deterministic source scanners used by Factory 1."""

from __future__ import annotations

import ast
import re
from pathlib import Path
from typing import Any


_SOURCE_SUFFIXES = {".py", ".js", ".mjs", ".ts", ".tsx", ".jsx", ".html"}
_MARKER_RE = re.compile(r"\b(?:TODO\s*:\s*INCOMPLETE|FIXME)\b", re.IGNORECASE)
_BUTTON_RE = re.compile(r"<button\b[^>]*>(.*?)</button\s*>", re.IGNORECASE | re.DOTALL)
_HANDLER_RE = re.compile(r"\b(?:onclick|onClick|handler|action)\s*=|\b(?:onclick|onClick|handler|action)\b", re.IGNORECASE)


def _source_files(directory: str | Path) -> list[Path]:
    root = Path(directory)
    if root.is_file():
        return [root] if root.suffix in _SOURCE_SUFFIXES else []
    return sorted(
        path
        for path in root.rglob("*")
        if path.is_file()
        and path.suffix in _SOURCE_SUFFIXES
        and not path.name.startswith("test_")
        and ".git" not in path.parts
        and "node_modules" not in path.parts
        and "__pycache__" not in path.parts
    )


def run_debug_pipeline(directory: str | Path = ".") -> dict[str, Any]:
    """Run all scanners and return their findings and an overall status."""
    result = {
        "dead_code": scan_for_dead_code(directory),
        "incomplete": scan_for_incomplete(directory),
        "dead_buttons": scan_for_dead_buttons(directory),
    }
    result["passed"] = not any(result.values())
    return result


def scan_for_dead_code(directory: str | Path = ".") -> list[str]:
    """Find Python functions/classes defined in a file but never referenced."""
    findings: list[str] = []
    for path in _source_files(directory):
        if path.suffix != ".py":
            continue
        try:
            source = path.read_text(encoding="utf-8")
            tree = ast.parse(source, filename=str(path))
        except (OSError, SyntaxError) as exc:
            findings.append(f"{path}: parse error: {exc}")
            continue
        definitions = [
            node for node in ast.walk(tree)
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
            and not node.name.startswith("__")
        ]
        names = {node.name for node in definitions}
        references = {
            node.id for node in ast.walk(tree) if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load)
        }
        for node in definitions:
            if node.name not in references and node.name not in {"main"}:
                findings.append(f"{path}:{node.lineno}: unreferenced definition '{node.name}'")
    return findings


def scan_for_incomplete(directory: str | Path = ".") -> list[str]:
    """Find explicit incomplete-work markers in source files."""
    findings: list[str] = []
    for path in _source_files(directory):
        try:
            for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                if _MARKER_RE.search(line) and not (path.name == "debug_pipeline.py" and "_MARKER_RE" in line):
                    findings.append(f"{path}:{line_no}: incomplete marker")
        except OSError as exc:
            findings.append(f"{path}: read error: {exc}")
    return findings


def scan_for_dead_buttons(directory: str | Path = ".") -> list[str]:
    """Find HTML button elements that have no recognizable handler."""
    findings: list[str] = []
    for path in _source_files(directory):
        if path.suffix not in {".html", ".js", ".mjs", ".ts", ".tsx", ".jsx"}:
            continue
        try:
            source = path.read_text(encoding="utf-8")
        except OSError as exc:
            findings.append(f"{path}: read error: {exc}")
            continue
        for match in _BUTTON_RE.finditer(source):
            opening_tag = source[max(0, source.rfind("<button", 0, match.start())) : source.find(">", source.rfind("<button", 0, match.start())) + 1]
            if not _HANDLER_RE.search(opening_tag) and not _HANDLER_RE.search(match.group(1)):
                line_no = source.count("\n", 0, match.start()) + 1
                findings.append(f"{path}:{line_no}: button has no handler")
    return findings


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Run Factory 1 source scanners")
    parser.add_argument("directory", nargs="?", default=".")
    args = parser.parse_args()
    result = run_debug_pipeline(args.directory)
    print(result)
    raise SystemExit(0 if result["passed"] else 1)
