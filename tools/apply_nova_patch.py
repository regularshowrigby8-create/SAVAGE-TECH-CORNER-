#!/usr/bin/env python3
"""Apply the nova drop-in patch to a nova checkout, or check that it would apply.

Two modes:

  --check   read-only. Compare every destination with the SHA-256 recorded in
            nova-patch/BASELINE.json. Exit 1 if any destination has drifted.
  --write   verify the entire plan first, back up replaced files, then copy.
            A filesystem failure during copying can still leave a partial write;
            the backups are kept so it can be recovered.

Only files under nova-patch/nova-tv/ are applied: documentation, diagnostics,
counts and crawler data beside it must never overwrite the checkout's root.

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
import json
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
    status: str          # identical | new | replaces | drifted
    detail: str = ""


def relative_files(patch_root: Path) -> list[str]:
    """Only the mirrored app tree belongs in the destination checkout."""
    return sorted(
        p.relative_to(patch_root).as_posix()
        for p in (patch_root / "nova-tv").rglob("*")
        if p.is_file() and "__pycache__" not in p.parts and not p.name.endswith(".pyc")
    )


def plan_one(relative: str, target_root: Path, baseline: dict[str, str | None]) -> Plan:
    source = PATCH_ROOT / relative
    target = target_root / relative
    if relative not in baseline:
        return Plan(relative, source, target, "drifted", "patch file has no recorded baseline")
    expected = baseline[relative]
    if not target.exists():
        if expected is None:
            return Plan(relative, source, target, "new", "target file does not exist")
        return Plan(relative, source, target, "drifted", "baseline file is missing")
    if not target.is_file():
        return Plan(relative, source, target, "drifted", "destination is not a file")
    if filecmp.cmp(source, target, shallow=False):
        return Plan(relative, source, target, "identical", "already patched")
    actual = hashlib.sha256(target.read_bytes()).hexdigest()
    if expected is None:
        return Plan(relative, source, target, "drifted", "new patch path already exists with different contents")
    if actual != expected:
        return Plan(relative, source, target, "drifted", "target differs from the recorded baseline")
    return Plan(relative, source, target, "replaces", "target matches the recorded baseline")


def render(plan: Plan) -> str:
    if plan.status == "new":
        return f"  + {plan.relative}  (new file, {plan.source.stat().st_size} B)"
    if plan.status == "drifted":
        return f"  ! {plan.relative}  ({plan.detail})"
    if plan.status == "identical":
        return f"  = {plan.relative}  (already patched)"
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
        print("error: the nova-tv patch tree is empty", file=sys.stderr)
        return 2
    try:
        baseline_doc = json.loads((PATCH_ROOT / "BASELINE.json").read_text(encoding="utf-8"))
        baseline = baseline_doc["files"]
        if not isinstance(baseline, dict):
            raise ValueError("files must be an object")
    except (OSError, ValueError, KeyError) as error:
        print(f"error: cannot read patch baseline: {error}", file=sys.stderr)
        return 2

    plans = [plan_one(rel, target_root, baseline) for rel in files]
    changed = [p for p in plans if p.status in ("new", "replaces")]
    identical = [p for p in plans if p.status == "identical"]
    conflicts = [p for p in plans if p.status == "drifted"]

    print(f"patch tree : {PATCH_ROOT / 'nova-tv'}")
    print(f"base commit: {baseline_doc.get('base_commit', 'unknown')}")
    print(f"target     : {target_root}")
    print(f"files      : {len(plans)}  to change {len(changed)}  already applied {len(identical)}  conflicts {len(conflicts)}")
    print()
    for plan in plans:
        if plan.status == "identical" and not args.all:
            continue
        print(render(plan))

    if conflicts:
        print("\nconflicts: nothing was written; use the recorded baseline or resolve the drift first", file=sys.stderr)
        return 1
    if not changed:
        print("\nnothing to do: the target already matches the patch")
        return 0

    print(f"\nsha256 of the files to write (first 12): {digest([p.source for p in changed])}")

    if args.check or not args.write:
        print("\n--check: no files were written. Re-run with --write to apply.")
        return 0

    apply(plans, write=True)
    print(f"\nwrote {len(changed)} file(s); previous versions are under {target_root / BACKUP_DIR}/")
    print("next: build and test the patched checkout before distributing it")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
