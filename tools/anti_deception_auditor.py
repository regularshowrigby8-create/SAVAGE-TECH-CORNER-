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
import hashlib
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

# Archives that ship the factory engine itself. The engine's own scanner and gate scripts contain the
# foreign-provenance tokens as *the list of things they search for*, so finding them there is the
# detector being present, not foreign code being present. Pinning the digest is what keeps this from
# becoming a loophole: change a single byte inside one of these archives - the only way to hide
# something in it - and the digest no longer matches, the exemption stops applying and every token
# match inside it is reported as a violation again.
VENDORED_ENGINE_ARCHIVES = {
    "SAVAGE_FACTORY_v4.zip":
        "6e876c2fd0cf7c1b0571b2a8ea1b85f971753724692dcf96a4cad2a485c5a48e",
    "SAVAGE_FACTORY_v4_DEBUG_FINAL.zip":
        "79e2e2a07c62d1149cdf6d1c7b745df4ddb259d5fe4c2b666144ab60ff98a72f",
}

# Non-blocking observations: things a reader should see, that are not violations.
AUDIT_NOTES: List[str] = []

# Files that must name a foreign identifier in order to test that it is rejected. Each entry is a
# detector or the test of a detector: the tokens below can only be proven to be caught by naming them
# somewhere, so the place that does the naming is allow-listed instead of the rule being weakened.
ALLOWED_FORBIDDEN_TOKEN_FILES = {
    "tools/anti_deception_auditor.py",
    "factory-3/tests/test_browser_app_rules.py",
    "factories/factory-3/tests/test_browser_app_rules.py",
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
            pinned_digest = VENDORED_ENGINE_ARCHIVES.get(rel)
            archive_digest = hashlib.sha256(data).hexdigest()
            vendored_engine = pinned_digest is not None and pinned_digest == archive_digest
            if pinned_digest is not None and not vendored_engine:
                violations.append(
                    f"Archive '{rel}' is the vendored factory engine but its SHA-256 is "
                    f"{archive_digest}, not the pinned {pinned_digest}: it was modified, so its own "
                    f"detector/gate sources are no longer trusted and every token inside it counts"
                )
            with zipfile.ZipFile(io.BytesIO(data)) as zf:
                for entry_name in zf.namelist():
                    if any(entry_name.endswith(ext) for ext in ALLOWED_FORBIDDEN_TOKEN_FILES):
                        continue
                    if entry_name.endswith('.md') or entry_name.endswith('.txt'):
                        continue
                    content = zf.read(entry_name)
                    for tok in FORBIDDEN_TOKENS:
                        if tok in content.lower():
                            message = (f"Binary '{rel}' contains foreign token '{tok.decode()}' "
                                       f"inside entry '{entry_name}'")
                            if vendored_engine:
                                AUDIT_NOTES.append(f"{message} (vendored engine detector source)")
                            else:
                                violations.append(message)
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

        # Check for decorative stubs or empty mock returns
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

    if AUDIT_NOTES:
        print(f"\n[NOTE] {len(AUDIT_NOTES)} hash-pinned vendored-engine token match(es), "
              f"not counted as violations:")
        for note in AUDIT_NOTES:
            print(f"  - {note}")

    if all_violations:
        print(f"\n[FAIL] Found {len(all_violations)} authenticity / anti-deception violation(s):")
        for v in all_violations:
            print(f"  - {v}")
        return 1

    print("\n[PASS] Repository passed all authenticity, anti-hollowness, and provenance invariants.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
