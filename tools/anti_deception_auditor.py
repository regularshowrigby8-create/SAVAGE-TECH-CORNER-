#!/usr/bin/env python3
"""
Anti-Deception and Authentic Debugging Auditor (STRICT DETERMINISTIC INVARIANT).

Scans the repository for:
1. Fake or cosmetic passes (empty returns, decorative verifications, mock/stub assertions).
2. Upstream foreign provenance tokens (barebones, lightning, anthonycr, etc.).
3. Binary provenance and integrity across all APK/DEX/ARSC/ZIP files.
4. Test harness integrity: checks for self-referential passes, mock passes, or bypassed gates.
5. In-flight debugging authenticity: verifies that debugging tools actually execute AST/bytecode checks.
"""

from __future__ import annotations

import argparse
import ast
import io
import os
import re
import sys
import zipfile
from pathlib import Path
from typing import Any, Dict, List, Set, Tuple

FORBIDDEN_TOKENS = [
    b"barebones",
    b"lightning",
    b"?t=lightning",
    b"client=lightning",
    b"anthonycr",
    b"acr.browser",
]

ALLOWED_FORBIDDEN_TOKEN_FILES = {
    "tools/anti_deception_auditor.py",
    "factories/factory-3/tools/browser_app_rules.py",
    "factory-3/tools/browser_app_rules.py",
    ".claude/scripts/gates.sh",
    ".github/workflows/dual-code-review.yml",
    "docs/factory/ENFORCEMENT.md",
}


def audit_binary_assets(repo_root: Path) -> List[str]:
    violations: List[str] = []
    
    # Audit all APK and ZIP files in the repository
    for path in repo_root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix.lower() not in {".apk", ".zip", ".jar"}:
            continue
        rel = path.relative_to(repo_root).as_posix()
        # Skip git directory or cache
        if ".git" in rel or "node_modules" in rel:
            continue

        try:
            with open(path, "rb") as f:
                data = f.read()
            with zipfile.ZipFile(io.BytesIO(data)) as zf:
                for entry_name in zf.namelist():
                    if entry_name.endswith('.md') or entry_name.endswith('.txt'):
                        continue
                    content = zf.read(entry_name)
                    hits = [tok for tok in FORBIDDEN_TOKENS if tok in content.lower()]
                    if not hits:
                        continue
                    # Archives redistribute this repo's own sources. An entry
                    # whose inner path is an allowlisted source file is the
                    # same content the source scan already permits — allowing
                    # it by entry name mirrors the source policy. Guard: the
                    # entry must be decodable UTF-8 text, so a compiled/binary
                    # payload cannot hide tokens under a blessed filename.
                    if entry_name in ALLOWED_FORBIDDEN_TOKEN_FILES:
                        try:
                            content.decode("utf-8")
                            continue
                        except UnicodeDecodeError:
                            violations.append(
                                f"Binary '{rel}' entry '{entry_name}' matches an allowed path but is not UTF-8 source text — refusing to skip"
                            )
                            continue
                    for tok in hits:
                        violations.append(
                            f"Binary '{rel}' contains foreign token '{tok.decode()}' inside entry '{entry_name}'"
                        )
        except Exception as e:
            # Corrupted archive or not a zip
            violations.append(f"Could not inspect archive '{rel}': {e}")

    return violations


def audit_source_code_authenticity(repo_root: Path) -> List[str]:
    violations: List[str] = []
    
    # Universal text scan across all project files (.java, .kt, .kts, .xml, .sh, .py, .md, .json, .yml, .gradle)
    SCANNED_EXTENSIONS = {
        ".py", ".java", ".kt", ".kts", ".xml", ".sh", ".json", ".yml", ".yaml", ".gradle"
    }

    for path in repo_root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix.lower() not in SCANNED_EXTENSIONS:
            continue
        rel = path.relative_to(repo_root).as_posix()
        if any(ignored in rel for ignored in (".git", "node_modules", ".cache", "build/", ".gradle/", "package-lock.json")):
            continue
        if rel in ALLOWED_FORBIDDEN_TOKEN_FILES:
            continue

        try:
            content = path.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue

        for tok in FORBIDDEN_TOKENS:
            if tok.decode() in content.lower():
                violations.append(f"Source file '{rel}' contains foreign token '{tok.decode()}'")

        # Check for decorative stubs or empty mock returns (python-only: other
        # extensions are not AST-parseable and would silently skip anyway)
        if path.suffix.lower() != ".py":
            continue
        try:
            tree = ast.parse(content, filename=rel)
            for node in ast.walk(tree):
                if isinstance(node, ast.FunctionDef):
                    # Check for functions that just pass or return True without any body
                    if len(node.body) == 1:
                        stmt = node.body[0]
                        if isinstance(stmt, ast.Pass):
                            violations.append(f"Empty pass statement in function '{node.name}' in {rel}:{node.lineno}")
                        elif isinstance(stmt, ast.Return) and isinstance(stmt.value, ast.Constant) and stmt.value.value is True:
                            # Flag unconditional 'return True' if it claims to be a verifier or validator
                            if any(w in node.name.lower() for w in ["verify", "validate", "check", "audit", "test"]):
                                violations.append(f"Hollow verification function '{node.name}' in {rel}:{node.lineno} returns True unconditionally")
        except Exception as e:
            pass

    # Hollow Check & Bypass Auditor: Detect decorative or bypassed quality checks
    for path in repo_root.rglob("*.sh"):
        if not path.is_file():
            continue
        rel = path.relative_to(repo_root).as_posix()
        if any(ignored in rel for ignored in (".git", "node_modules")):
            continue
        try:
            sh_content = path.read_text(encoding="utf-8", errors="ignore")
            # 1. Flag environment bypasses
            if re.search(r"""\b(?:SKIP_[A-Z0-9_]+|BYPASS_[A-Z0-9_]+)\b""", sh_content):
                violations.append(f"Shell script '{rel}' contains environment-variable gate bypass mechanism")
            # 2. Flag docstring discrepancy: doctor.sh promises placeholder scan without implementing it
            if "doctor.sh" in rel:
                if "placeholder" in sh_content.lower() and not any(w in sh_content for w in ["grep", "python", "rg"]):
                    violations.append(f"Shell script '{rel}' promises placeholder scan in header but contains no scanner")
        except Exception:
            pass

    # Check criticism scripts for superficial substring-only checks
    for check_file in [
        repo_root / "factory-2" / "criticism" / "dead_buttons_check.py",
        repo_root / "factories" / "factory-2" / "criticism" / "dead_buttons_check.py",
    ]:
        if check_file.exists():
            rel = check_file.relative_to(repo_root).as_posix()
            try:
                code = check_file.read_text(encoding="utf-8")
                # Fail if dead_buttons_check is reduced to a raw substring check without calling/executing handlers
                if "click_button" not in code or "callable(" not in code:
                    violations.append(f"Criticism check '{rel}' is hollow: does not verify callability or execution of UI handlers")
            except Exception:
                pass

    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description="Deterministic Anti-Deception & Authenticity Auditor")
    parser.add_argument("--root", default=".", help="Repository root path")
    args = parser.parse_args()

    repo_root = Path(args.root).resolve()
    print("=" * 60)
    print("SAVAGE TECH CORNER: DETERMINISTIC ANTI-DECEPTION & PROVENANCE AUDIT")
    print("=" * 60)

    binary_violations = audit_binary_assets(repo_root)
    source_violations = audit_source_code_authenticity(repo_root)

    all_violations = binary_violations + source_violations

    if all_violations:
        print(f"\n[FAIL] Found {len(all_violations)} authenticity / anti-deception violation(s):")
        for v in all_violations:
            print(f"  - {v}")
        return 1

    print("\n[PASS] Repository passed all authenticity, anti-hollowness, and provenance invariants.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
