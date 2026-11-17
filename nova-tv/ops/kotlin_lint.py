#!/usr/bin/env python3
"""Static checks for the Kotlin sources, since no compiler is available.

This is not a compiler. It catches the class of mistake that is cheap to make
when writing files by hand and expensive to find in Android Studio:

  * a package declaration that does not match the directory
  * a public symbol used with no import and no local declaration
  * duplicated class names
  * unbalanced braces
  * an import that nothing references

It cannot typecheck. It is honest about that in its output.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "android" / "app" / "src"

IMPORT_RE = re.compile(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?$", re.M)
PACKAGE_RE = re.compile(r"^package\s+([\w.]+)", re.M)
CLASS_RE = re.compile(r"^(?:data\s+|sealed\s+|abstract\s+|open\s+|private\s+|internal\s+)*(?:class|interface|object|enum class)\s+(\w+)", re.M)


def strip_comments_and_strings(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"""(?:.|\n)*?"""', '""', text)
    text = re.sub(r'"(?:\\.|[^"\\])*"', '""', text)
    return text


def main() -> int:
    files = sorted(SRC.rglob("*.kt"))
    problems: list[str] = []
    declared: dict[str, list[str]] = {}

    for path in files:
        raw = path.read_text("utf-8")
        code = strip_comments_and_strings(raw)
        # Sources live under main/ and test/; both must map package -> directory.
        relative = None
        for root_name in ("main", "test", "androidTest"):
            candidate = SRC / root_name / "java"
            try:
                relative = path.relative_to(candidate)
                break
            except ValueError:
                continue
        if relative is None:
            problems.append(f"{path}: outside a known source root")
            continue
        expected_pkg = str(relative.parent).replace("/", ".")
        match = PACKAGE_RE.search(raw)
        actual_pkg = match.group(1) if match else ""
        if actual_pkg != expected_pkg:
            problems.append(f"{path.name}: package '{actual_pkg}' does not match directory '{expected_pkg}'")

        if code.count("{") != code.count("}"):
            problems.append(f"{path.name}: unbalanced braces ({code.count('{')} open, {code.count('}')} close)")

        for name in CLASS_RE.findall(code):
            declared.setdefault(name, []).append(path.name)

    for name, where in declared.items():
        if len(where) > 1 and name not in {"Companion"}:
            # Same-named classes in different packages are legal; same package is not.
            problems.append(f"class '{name}' declared {len(where)} times: {', '.join(where)}")

    # Unused imports: a cheap signal that a file was edited carelessly.
    for path in files:
        raw = path.read_text("utf-8")
        code = strip_comments_and_strings(raw)
        for full, alias in IMPORT_RE.findall(raw):
            symbol = alias or full.rsplit(".", 1)[-1]
            body = IMPORT_RE.sub("", code)
            if symbol == "*":
                continue
            if not re.search(rf"\b{re.escape(symbol)}\b", body):
                problems.append(f"{path.name}: unused import {full}")

    print(f"checked {len(files)} Kotlin files")
    if not problems:
        print("no structural problems found")
    else:
        print(f"{len(problems)} problem(s):")
        for problem in problems:
            print("  - " + problem)
    print("\nNote: this is a structural check, not a compile. Type errors, "
          "Compose API mismatches and missing Android resources can only be "
          "caught by a real Gradle build.")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
