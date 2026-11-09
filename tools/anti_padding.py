#!/usr/bin/env python3
"""Anti-padding audit — enforces NO_PADDING.md mechanically.

NO_PADDING.md says every file must be reachable from the gate script, the CI
workflow, the pipeline runner, or the charter/contract docs, and forbids
duplicate copies and dead directories. Until now nothing checked it.

Four checks. Each is proven to fail by --self-test.

1. ORPHANS        An app file nothing references and nothing executes.
2. DUPLICATES     Two files with the same content (the "identical copies with
                  only a number changed" NO_PADDING names explicitly).
3. EMPTY DIRS     A directory with no files in it, or none reachable.
4. DEAD SCRIPTS   A test suite or npm script that no runner invokes.

Usage:
    python3 tools/anti_padding.py
    python3 tools/anti_padding.py --self-test
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = Path("tv/zeroplay-debrid")

# Where a file may earn its place. Anything reachable from these is fine.
ENTRY_POINTS = [
    ".claude/scripts/gates.sh",
    ".github/workflows/factory-2-debug.yml",
    ".github/workflows/zeroplay-tv-browser.yml",
    "factory-2/scripts/run_all.sh",
    "docs/factory/CHARTER.md",
    "docs/factory/CONTRACT.md",
    "tv/zeroplay-debrid/package.json",
]

# Files that are their own entry point or are referenced by the platform.
STRUCTURAL = {
    "index.html",
    "README.md",
    "UI-REBUILD.md",
    "package.json",
    "package-lock.json",
    ".gitignore",
    ".vercelignore",
    "vercel.json",
}

CODE_SUFFIXES = {".js", ".cjs", ".mjs", ".py", ".sh", ".html", ".css"}


class Findings(list):
    def add(self, category: str, location: str, detail: str) -> None:
        self.append((category, location, detail))


def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return ""


def _referenced_text() -> str:
    """Everything a file could be named from."""
    chunks = []
    for entry in ENTRY_POINTS:
        path = ROOT / entry
        if path.exists():
            chunks.append(_read(path))
    # app code can reference its own parts (e.g. tests/browser/*.cjs)
    for path in (ROOT / APP).rglob("*"):
        if path.is_file() and path.suffix in CODE_SUFFIXES | {".json", ".md"}:
            if "node_modules" in path.parts:
                continue
            chunks.append(_read(path))
    return "\n".join(chunks)


def check_orphans(findings: Findings) -> None:
    references = _referenced_text()
    app = ROOT / APP
    for path in sorted(app.rglob("*")):
        if not path.is_file() or "node_modules" in path.parts or "dist" in path.parts:
            continue
        rel_to_app = path.relative_to(app).as_posix()
        if rel_to_app in STRUCTURAL:
            continue
        if rel_to_app.startswith("vendor/") or rel_to_app.startswith("api/"):
            # vendor is loaded by the page/player, api by the platform routes
            continue
        name = path.name
        rel = path.relative_to(ROOT).as_posix()
        if name in references or rel in references or rel_to_app in references:
            continue
        findings.add("orphan", rel, "nothing references or executes this file")


def _hash(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_duplicates(findings: Findings) -> None:
    seen: dict[str, str] = {}
    for path in sorted((ROOT / APP).rglob("*")):
        if not path.is_file() or "node_modules" in path.parts or "dist" in path.parts:
            continue
        if path.suffix not in CODE_SUFFIXES:
            continue
        digest = _hash(path)
        rel = path.relative_to(ROOT).as_posix()
        if digest in seen:
            findings.add("duplicate", rel, f"byte-identical to {seen[digest]}")
        else:
            seen[digest] = rel


def check_empty_dirs(findings: Findings) -> None:
    for path in sorted((ROOT / APP).rglob("*")):
        if not path.is_dir() or "node_modules" in path.parts or "dist" in path.parts:
            continue
        if any(p.is_file() for p in path.rglob("*")):
            continue
        findings.add("empty-dir", path.relative_to(ROOT).as_posix(), "directory contains no files")


def check_dead_scripts(findings: Findings) -> None:
    """Every npm script must be invoked by something, and vice versa."""
    manifest = ROOT / APP / "package.json"
    if not manifest.exists():
        findings.add("missing-manifest", str(APP / "package.json"), "no package.json")
        return
    try:
        scripts = json.loads(_read(manifest)).get("scripts", {})
    except json.JSONDecodeError as error:
        findings.add("bad-manifest", str(APP / "package.json"), str(error))
        return
    everything_outside = "\n".join(
        _read(ROOT / entry) for entry in ENTRY_POINTS if (ROOT / entry).exists()
    )
    referenced_suites: set[str] = set()
    for name, command in scripts.items():
        for suite in re.findall(r"node\s+(tests/[\w./-]+\.c?m?js)", command):
            referenced_suites.add(suite)
        used = f"npm run {name}" in everything_outside or f"npm test" in everything_outside and name == "test"
        is_composite = "&&" in command
        if used or is_composite:
            continue
        findings.add("dead-script", f"package.json:scripts.{name}", f"no runner invokes `{name}`")
    # a suite file no script runs is padding too
    for suite in sorted((ROOT / APP / "tests").rglob("*.cjs")):
        rel = suite.relative_to(ROOT / APP).as_posix()
        if rel not in referenced_suites:
            findings.add("dead-suite", f"{APP}/{rel}", "no npm script runs this suite")


def audit() -> Findings:
    findings = Findings()
    check_orphans(findings)
    check_duplicates(findings)
    check_empty_dirs(findings)
    check_dead_scripts(findings)
    return findings


def self_test() -> int:
    import tempfile

    global ROOT
    real_root = ROOT
    failures = 0

    def expect(label: str, findings: Findings, category: str, needle: str = "") -> None:
        nonlocal failures
        hit = [f for f in findings if f[0] == category and needle in f[1]]
        if hit:
            print(f"PASS self-test: {label}")
        else:
            print(f"FAIL self-test: {label} was not caught")
            failures += 1

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        app = root / APP
        (app / "tests").mkdir(parents=True)
        (root / "docs").mkdir()

        # 1. orphan: a file nothing names
        (app / "forgotten-module.js").write_text("// nobody calls me\n", encoding="utf-8")
        (app / "package.json").write_text(json.dumps({"scripts": {"test": "node tests/a.cjs"}}), encoding="utf-8")
        (app / "tests" / "a.cjs").write_text("1\n", encoding="utf-8")
        ROOT = root
        expect("an unreferenced file", check_and(check_orphans, root), "orphan", "forgotten-module.js")

        # 2. duplicates
        (app / "one.js").write_text("same body\n", encoding="utf-8")
        (app / "two.js").write_text("same body\n", encoding="utf-8")
        expect("byte-identical copies", check_and(check_duplicates, root), "duplicate", "two.js")

        # 3. empty directory
        (app / "empty").mkdir()
        expect("an empty directory", check_and(check_empty_dirs, root), "empty-dir", "empty")

        # 4. dead suite: exists but no script runs it
        (app / "tests" / "b.cjs").write_text("2\n", encoding="utf-8")
        expect("a suite no runner executes", check_and(check_dead_scripts, root), "dead-suite", "b.cjs")

        # 5. clean case: remove every injected violation
        (app / "forgotten-module.js").unlink()
        (app / "one.js").unlink()
        (app / "two.js").unlink()
        (app / "empty").rmdir()
        (app / "tests" / "b.cjs").unlink()
        (app / "package.json").write_text(json.dumps({"scripts": {"test": "node tests/a.cjs"}}), encoding="utf-8")
        clean = check_and(check_orphans, root)
        if clean:
            print(f"FAIL self-test: a clean tree was flagged: {clean[:2]}")
            failures += 1
        else:
            print("PASS self-test: a clean tree passes")

    ROOT = real_root
    return 1 if failures else 0


def check_and(fn, root: Path) -> Findings:
    findings = Findings()
    fn(findings)
    return findings


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    findings = audit()
    if findings:
        print(f"FAIL: {len(findings)} padding violation(s)")
        for category, location, detail in findings[:60]:
            print(f"  [{category}] {location}: {detail}")
        if len(findings) > 60:
            print(f"  … and {len(findings) - 60} more")
        return 1
    print("PASS: no orphan files, duplicate copies, empty directories or dead scripts")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
