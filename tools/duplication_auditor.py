#!/usr/bin/env python3
"""
Structural Code Duplication Auditor.
Ensures zero unapproved drift between root directories and nested factories mirrors.

Semantics (fail-closed):
1. Every mirrored CODE file (agents/criticism/manager/scripts/tests/app/tools
   under factory-2 and factory-3) must exist on BOTH sides and be byte-identical.
2. A file existing on only one side is drift — silently passing on a missing
   mirror was the previous logic hole.
3. Runtime artifacts (reports, logs, __pycache__, proposals) are legitimately
   one-sided and excluded.
"""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent

# Subtrees that must stay byte-identical between a root factory and its mirror.
MIRRORED_SUBTREES = [
    ("factory-2", "factories/factory-2", "agents"),
    ("factory-2", "factories/factory-2", "criticism"),
    ("factory-2", "factories/factory-2", "manager"),
    ("factory-2", "factories/factory-2", "scripts"),
    ("factory-2", "factories/factory-2", "tests"),
    ("factory-2", "factories/factory-2", "app"),
    ("factory-3", "factories/factory-3", "tools"),
]

EXCLUDED_DIRS = {"__pycache__", "reports", "logs", "proposals", "inbox"}
# Runtime-generated files legitimately exist only on the root side.
EXCLUDED_FILES = {"ai_workers_activity_log.sh"}
CODE_SUFFIXES = {".py", ".sh", ".js", ".cjs", ".mjs", ".ts", ".json", ".md", ".html"}


def _code_files(base: Path) -> dict[str, Path]:
    files: dict[str, Path] = {}
    if not base.is_dir():
        return files
    for path in base.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(base)
        if any(part in EXCLUDED_DIRS for part in rel.parts):
            continue
        if path.name in EXCLUDED_FILES:
            continue
        if path.suffix.lower() not in CODE_SUFFIXES:
            continue
        files[rel.as_posix()] = path
    return files


def audit_duplication() -> list[str]:
    """Synchronize-verify mirrored trees; assert root canonical status."""
    violations: list[str] = []
    for root_side_str, mirror_side_str, subtree in MIRRORED_SUBTREES:
        root_base = ROOT_DIR / root_side_str / subtree
        mirror_base = ROOT_DIR / mirror_side_str / subtree
        if not root_base.is_dir() and not mirror_base.is_dir():
            continue  # subtree genuinely absent on both sides
        root_files = _code_files(root_base)
        mirror_files = _code_files(mirror_base)

        for only_root in sorted(set(root_files) - set(mirror_files)):
            violations.append(f"One-sided file: {root_side_str}/{subtree}/{only_root} has no mirror in {mirror_side_str}")
        for only_mirror in sorted(set(mirror_files) - set(root_files)):
            violations.append(f"One-sided file: {mirror_side_str}/{subtree}/{only_mirror} has no mirror in {root_side_str}")
        for shared in sorted(set(root_files) & set(mirror_files)):
            h1 = hashlib.sha256(root_files[shared].read_bytes()).hexdigest()
            h2 = hashlib.sha256(mirror_files[shared].read_bytes()).hexdigest()
            if h1 != h2:
                violations.append(f"Drift detected between mirrors: {root_side_str}/{subtree}/{shared} != {mirror_side_str}/{subtree}/{shared}")
    return violations


def main() -> int:
    errs = audit_duplication()
    if errs:
        print("[DUPLICATION-AUDITOR] FAIL:")
        for e in errs:
            print(f"  - {e}")
        return 1
    print("[DUPLICATION-AUDITOR] PASS: All mirrored code trees synchronized without unapproved drift.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
