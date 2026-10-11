#!/usr/bin/env python3
"""Apply only mechanically provable unfinished-code repairs.

This is deliberately narrower than a semantic auto-fixer: it repairs empty
Kotlin callbacks/when branches by making their intentional Unit result
explicit. It never edits imported assets, catches, TODO text, tests, or
constant-return functions. Dry-run is the default; --apply requires the
factory approval environment variable.
"""
from __future__ import annotations

import argparse
import os
import re
from pathlib import Path

KOTLIN = {".kt", ".kts"}
CALLBACK = re.compile(r"(?P<prefix>(?:->|=)\s*)\{\s*\}")


def eligible(path: Path) -> bool:
    text = path.as_posix()
    return path.suffix in KOTLIN and "/src/main/" in text and "/assets/" not in text


def repair(path: Path, apply: bool) -> int:
    before = path.read_text(encoding="utf-8")
    after, count = CALLBACK.subn(r"\g<prefix>{ Unit }", before)
    if count and apply:
        path.write_text(after, encoding="utf-8")
    if count:
        print(f"{'APPLY' if apply else 'PROPOSE'} {path}: {count} explicit Unit callbacks")
    return count


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if args.apply and os.environ.get("SEARCHHH_ALLOW_UNFINISHED_REPAIR") != "1":
        raise SystemExit("--apply requires SEARCHHH_ALLOW_UNFINISHED_REPAIR=1")
    total = 0
    for path in sorted(args.root.rglob("*")):
        if path.is_file() and eligible(path):
            total += repair(path, args.apply)
    print(f"{'Applied' if args.apply else 'Proposed'} {total} bounded repairs")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
