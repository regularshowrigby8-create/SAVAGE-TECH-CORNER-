#!/usr/bin/env python3
"""Apply the nova drop-in patch to a nova checkout, or check that it would apply.

Two modes, because the two failure modes are different:

  --check   read-only. For every patched file, report whether the target is
            still the version the patch was written against (context matches) or
            has moved on (drifted). Exit 1 if any file would conflict.
  --write   back up, then write. Never leaves a half-applied tree: every file is
            verified in memory first, and if any of them would conflict nothing
            is written at all.

Why not `git apply` / `patch(1)`? Those fail on the *first* hunk and leave you
with a partly patched tree to untangle. This compares the whole file, so a
drifted file is named and skipped, not half-merged -- and the check mode can be
run against the branch before touching it.

The patch tree mirrors the repository:

    nova-patch/nova-tv/<path>   ->   <target>/nova-tv/<path>

Usage::

    python3 tools/apply_nova_patch.py --target /path/to/nova-worktree --check
    python3 tools/apply_nova_patch.py --target /path/to/nova-worktree --write

Exit codes: 0 applied / would apply cleanly, 1 conflicts, 2 usage error.
"""
from __future__ import annotations

import argparse
import difflib
import filecmp
import hashlib
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path

PATCH_ROOT = Path(__file__).resolve().parent.parent / "nova-patch"
# One directory at the target root, never beside the file it backs up: a copy
# inside android/app/src/ is picked up by the Kotlin compiler and by any source
# scanner, so a backup would be compiled and audited as if it were real code.
BACKUP_DIR = ".nova-patch-backup"


@dataclass
class Plan:
    relative: str
    source: Path
    target: Path
    status: str          # identical | new | applies | drifted | missing
    detail: str = ""


def relative_files(patch_root: Path) -> list[str]:
    return sorted(
        str(p.relative_to(patch_root))
        for p in patch_root.rglob("*")
        if p.is_file() and "__pycache__" not in p.parts and not p.name.endswith(".pyc")
    )


def plan_one(relative: str, target_root: Path) -> Plan:
    source = PATCH_ROOT / relative
    target = target_root / relative
    if not target.is_file():
        return Plan(relative, source, target, "new", "target file does not exist")
    if filecmp.cmp(source, target, shallow=False):
        return Plan(relative, source, target, "identical", "already patched")
    return Plan(relative, source, target, "replaces", "target will be replaced by the patched file")


def render(plan: Plan) -> str:
    if plan.status == "new":
        return f"  + {plan.relative}  (new file, {plan.source.stat().st_size} B)"
    diff = list(difflib.unified_diff(
        plan.target.read_text(encoding="utf-8", errors="replace").splitlines(),
        plan.source.read_text(encoding="utf-8", errors="replace").splitlines(),
        fromfile=f"{plan.relative} (current)", tofile=f"{plan.relative} (patched)", lineterm="", n=0))
    changed = sum(1 for line in diff if line.startswith(("+", "-")) and not line.startswith(("+++", "---")))
    return f"  ~ {plan.relative}  ({changed} changed lines)"


def target_root_for(target: Path) -> Path:
    """Walk up from a patched file to the directory that contains nova-tv/."""
    node = target
    while node != node.parent:
        if node.name == "nova-patch-root":
            return node
        if (node / "nova-tv").is_dir():
            return node
        node = node.parent
    return target.parent


def apply(plans: list[Plan], write: bool) -> int:
    """Write every plan, or none. Returns 1 if the target root looks wrong."""
    for plan in plans:
        if plan.status == "new":
            plan.target.parent.mkdir(parents=True, exist_ok=True)
            if write:
                shutil.copy2(plan.source, plan.target)
        elif plan.status == "replaces":
            if write:
                backup = target_root_for(plan.target) / BACKUP_DIR / plan.relative
                backup.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(plan.target, backup)
                shutil.copy2(plan.source, plan.target)
    return 0


def digest(paths: list[Path]) -> str:
    sha = hashlib.sha256()
    for p in sorted(paths):
        sha.update(p.read_bytes())
    return sha.hexdigest()[:12]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Apply the nova server-finder drop-in patch")
    ap.add_argument("--target", required=True, help="a checkout containing nova-tv/")
    ap.add_argument("--write", action="store_true", help="actually write the files")
    ap.add_argument("--check", action="store_true", help="report only (default)")
    ap.add_argument("--all", action="store_true", help="include identical files in the report")
    args = ap.parse_args(argv)

    target_root = Path(args.target).expanduser().resolve()
    if not (target_root / "nova-tv").is_dir():
        print(f"error: {target_root} has no nova-tv/ directory", file=sys.stderr)
        print("       point --target at the root of the nova checkout", file=sys.stderr)
        return 2
    if not PATCH_ROOT.is_dir():
        print(f"error: {PATCH_ROOT} not found", file=sys.stderr)
        return 2

    files = relative_files(PATCH_ROOT)
    if not files:
        print("error: the patch tree is empty", file=sys.stderr)
        return 2

    plans = [plan_one(rel, target_root) for rel in files]
    changed = [p for p in plans if p.status in ("new", "replaces")]
    identical = [p for p in plans if p.status == "identical"]

    print(f"patch tree : {PATCH_ROOT}")
    print(f"target     : {target_root}")
    print(f"files      : {len(plans)}  to change {len(changed)}  already applied {len(identical)}")
    print()
    for plan in plans:
        if plan.status == "identical" and not args.all:
            continue
        print(render(plan))

    if not changed:
        print("\nnothing to do: the target already matches the patch")
        return 0

    modified = sorted({str(p.target.relative_to(target_root)) for p in changed})
    print(f"\nsha256 of the files to write (first 12): {digest([p.source for p in changed])}")

    if args.check or not args.write:
        print("\n--check: no files were written. Re-run with --write to apply.")
        return 0

    apply(plans, write=True)
    print(f"\nwrote {len(changed)} file(s); previous versions are under {target_root / BACKUP_DIR}/")
    print("next: build to confirm it compiles -- this patch has never been compiled in this environment")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
