#!/usr/bin/env python3
"""Build the sealed component as a standalone tree, exactly as its own removal record describes it.

The component keeps a removal record in `SEAL.json`: the directory to delete, the tooling files that
belong to it, its tests, its workflows and its document. That record is what makes the component one
removable thing, and it is also the honest answer to "what is the component?" - so the published tree
is built *from that record* rather than from a second list that would drift away from it.

    python3 tools/ci/build_component_snapshot.py --into /tmp/snapshot \\
        --self-check workspace/self-check.json --source-commit "$(git rev-parse HEAD)" \\
        --source-branch arena/5223ef6f-savage-tech-corner

A path the record names and the tree does not have is an error: a component published minus a file it
claims to carry is worse than one not published at all. The self-check report is required for the same
reason - a published tree must carry the verdict of the run that published it, and no verdict is not a
passing one.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SEALED = REPO_ROOT / "sealed-factories"
SEAL_FILE = SEALED / "SEAL.json"

#: Written by the sealer and by runs: not part of the component, and noisy in a published tree.
SKIP_DIRS = ("manifest", "__pycache__")


def removal_record() -> dict:
    document = json.loads(SEAL_FILE.read_text(encoding="utf-8"))
    record = document.get("removal")
    if not record:
        raise SystemExit("the seal carries no removal record, so there is nothing to publish from")
    return record


def component_paths() -> list[Path]:
    record = removal_record()
    paths = []
    for group in ("also_remove", "tests", "workflows", "docs"):
        for relative in record.get(group, []):
            path = REPO_ROOT / relative
            if not path.is_file():
                raise SystemExit(f"the removal record names {relative}, which is not in this tree: "
                                 f"publishing a component minus a file it claims to carry is not a "
                                 f"smaller component")
            paths.append(path)
    return paths


def copy_component(into: Path) -> dict:
    if into.exists() and any(into.iterdir()):
        raise SystemExit(f"{into} is not empty; refusing to build over whatever is there")
    into.mkdir(parents=True, exist_ok=True)
    copied = 0
    for path in SEALED.rglob("*"):
        if any(part in SKIP_DIRS for part in path.relative_to(SEALED).parts):
            continue
        if not path.is_file():
            continue
        target = into / "sealed-factories" / path.relative_to(SEALED)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        copied += 1
    for path in component_paths():
        target = into / path.relative_to(REPO_ROOT)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        copied += 1
    return {"files": copied}


def write_readme(into: Path, self_check: dict, source_commit: str, source_branch: str) -> None:
    record = removal_record()
    findings = self_check.get("blocking", [])
    states: dict[str, int] = {}
    for state in self_check.get("states", []):
        states[state.get("code", "?")] = states.get(state.get("code", "?"), 0) + 1
    text = f"""# The sealed factory, on the branch it owns

This branch carries the sealed factory component and nothing else. It is not `main`: the component is
published here so it can be reviewed, run and removed on its own, and it will only ever be proposed for
`main` once this branch's own evidence says it is functional. Nothing in this branch was pushed by hand.

- **Seal root**: `{self_check.get('seal_root', '')}` (`sha256(SEAL.json)`, recorded in
  `sealed-factories/SEAL.sha256`)
- **Records**: {self_check.get('records', 0)} sealed factories, {self_check.get('carried', 0)} carrying
  content, {self_check.get('licence_texts', 0)} licence texts in the seal, {self_check.get('store_texts', 0)}
  in the licence store
- **Self-check verdict**: `{self_check.get('verdict', 'ABSENT')}` - {len(findings)} blocking finding(s);
  named states: {states or 'none'} (a licence that does not permit copying, and slices that define no
  stage, worker, gate or prompt, are states and are named as such rather than counted as fitness)
- **Published from**: `{source_commit}` on `{source_branch}`

## What it is

One removable component: the sealed factory set (`sealed-factories/`, {self_check.get('records', 0)}
factories with their payloads and licence texts, sealed and hash-verified), the tooling that grows and
serves it ({len(record.get('also_remove', []))} files), its tests
({len(record.get('tests', []))}), its workflows ({len(record.get('workflows', []))}) and its document.
Removing it is what `SEAL.json`'s `removal` record describes, and no file outside it is needed to run it.

## Running it

```
python3 sealed-factories/runtime/cli.py verify                      # recompute every sealed hash
python3 sealed-factories/runtime/cli.py selfcheck                   # recheck the set with the factories it carries
python3 sealed-factories/runtime/cli.py run --max-model-calls 0     # the swarm, plan mode, no keys
python3 -m unittest discover -s shell/tests                         # the component's own tests
```

The licence wall travels with the component: only factories whose licences permit copying have their
content carried, every carried record has its licence text beside it, and records whose licences do not
permit it are blueprint-only - named as withheld, never quietly dropped.
"""
    (into / "SEALED-COMPONENT.md").write_text(text, encoding="utf-8")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Build the component as a standalone tree")
    parser.add_argument("--into", required=True)
    parser.add_argument("--self-check", required=True, help="the self-check report of this run")
    parser.add_argument("--source-commit", default="")
    parser.add_argument("--source-branch", default="")
    args = parser.parse_args(argv[1:])

    report_path = Path(args.self_check)
    if not report_path.is_file():
        print(f"the self-check report {report_path} does not exist: a tree published without its "
              f"verdict would be a claim, not evidence", file=sys.stderr)
        return 1
    self_check = json.loads(report_path.read_text(encoding="utf-8"))
    if self_check.get("verdict") != "FUNCTIONAL":
        print(f"the self-check verdict is {self_check.get('verdict')}; only a functional set is "
              f"published", file=sys.stderr)
        return 1

    into = Path(args.into).resolve()
    if REPO_ROOT == into or REPO_ROOT in into.parents:
        print(f"refusing to build the snapshot inside the repository ({into})", file=sys.stderr)
        return 1
    counts = copy_component(into)
    write_readme(into, self_check, args.source_commit, args.source_branch)
    payloads = list((into / "sealed-factories" / "payloads").glob("*"))
    print(f"component snapshot: {counts['files']} files, {len(payloads)} payloads, "
          f"self-check {self_check.get('verdict')}, seal {self_check.get('seal_root', '')[:16]}… "
          f"-> {into}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
