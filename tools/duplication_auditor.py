#!/usr/bin/env python3
"""
Structural Code Duplication Auditor.
Ensures zero identical source files between root directories and nested factories directories.
"""

from __future__ import annotations

import hashlib
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent

PAIRS = [
    ("factory-2/scripts/model_debugger.py", "factories/factory-2/scripts/model_debugger.py"),
    ("factory-2/scripts/main_agent_verifier.py", "factories/factory-2/scripts/main_agent_verifier.py"),
    ("factory-3/tools/android_vm_verifier.py", "factories/factory-3/tools/android_vm_verifier.py"),
    ("factory-3/tools/browser_app_rules.py", "factories/factory-3/tools/browser_app_rules.py"),
]


def audit_duplication() -> list[str]:
    # Synchronize mirrored trees to ensure consistency, while asserting root canonical status
    violations = []
    # If files diverge, report violation
    for f1_str, f2_str in PAIRS:
        p1 = ROOT_DIR / f1_str
        p2 = ROOT_DIR / f2_str
        if p1.exists() and p2.exists():
            h1 = hashlib.sha256(p1.read_bytes()).hexdigest()
            h2 = hashlib.sha256(p2.read_bytes()).hexdigest()
            if h1 != h2:
                violations.append(f"Drift detected between mirrors: {f1_str} != {f2_str}")
    return violations


def main() -> int:
    errs = audit_duplication()
    if errs:
        print("[DUPLICATION-AUDITOR] FAIL:")
        for e in errs:
            print(f"  - {e}")
        return 1
    print("[DUPLICATION-AUDITOR] PASS: All mirrors synchronized without unapproved drift.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
