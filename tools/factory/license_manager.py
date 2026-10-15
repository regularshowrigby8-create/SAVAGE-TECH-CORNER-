#!/usr/bin/env python3
"""The licence store: one index over the licence texts the sealed component carries, and the tool
that imports the ones it is missing.

Every sealed factory that publishes a licence has its text stored as `sealed-factories/licenses/
{id}-LICENSE`, written by the sealer from the bytes the vendoring tool carried. Until now nothing held
those texts together: the seal recorded *which* file belongs to which record and never what the text
says or whether it still is what it was. This tool is that missing layer. It

  * indexes the store - one entry per text with its size, its sha256 and what the text classifies as -
    into `sealed-factories/LICENSES.json`, which is itself pinned by the seal (`COMPONENT_DATA`), so a
    changed licence text is a seal verification failure rather than something nobody would notice;
  * verifies the store against itself, against the seal and against the registries: a missing text, a
    stale index, an orphan, a record carrying content with no licence beside it, or a licence the
    registry declares and the store never imported, each one named;
  * imports the texts that are missing - from the vendored record when it already carries the bytes
    (no network needed), otherwise from the repository's own published file over the GitHub API, into
    both the store and the vendored record so a re-seal keeps them.

What it will not do, deliberately:

  * it never writes `license` or `content_redistributed`. The licence wall is decided by
    `seal_factories.content_may_be_copied` from the registry's licence and a recorded licence file; a
    tool that could flip either would be a way around the wall, so this one cannot reach them;
  * it never overwrites a stored text. Gap-filling only - a text that is present and disagrees with
    what upstream publishes today is the re-scan's business (`license_rescan.py` widens a `NOASSERTION`
    and escalates a disagreement), not something to be silently replaced here;
  * it never invents a licence. A repository that publishes no licence file, or one it cannot read,
    is reported as exactly that.

    python3 tools/factory/license_manager.py index
    python3 tools/factory/license_manager.py verify
    python3 tools/factory/license_manager.py import
    python3 tools/factory/license_manager.py show addyosmani-agent-skills
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools" / "factory"))

import license_text  # noqa: E402
import license_rescan  # noqa: E402
from vendor_needed import REGISTRIES, entries_of  # noqa: E402

SEALED = REPO_ROOT / "sealed-factories"
STORE = SEALED / "licenses"
INDEX = SEALED / "LICENSES.json"
SEAL_FILE = SEALED / "SEAL.json"

#: The canonical name the sealer writes: `{id}-LICENSE`. The upstream name stays in the vendored
#: record, because it is the record's provenance and not the store's filename.
SUFFIX = "-LICENSE"

#: What may live in the store: the canonical name, and nothing else. A file that is not a licence text
#: is not indexed (indexing it would make the index describe something other than licences) and it is
#: reported by `verify` - an unexpected file in the store is how a store stops being a store.
STORE_FILE = re.compile(r"^[\w][\w.-]*-LICENSE$")


def shown(path: Path) -> str:
    """A path a reader can recognise, whether or not it lives inside this repository.

    The store is not always inside the checkout being verified: a published copy, a temporary store in a
    test and a verification run in a worktree all hand this tool paths that have no relative form here.
    `relative_to` raises for those - a crash while *reporting* a problem, which is worse than the problem.
    """
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sealed_records() -> list[dict]:
    """The records as the seal describes them, or an empty list when there is no seal to read."""
    if not SEAL_FILE.is_file():
        return []
    document = json.loads(SEAL_FILE.read_text(encoding="utf-8"))
    return document.get("factories", [])


def build_index(store: Path = STORE) -> dict:
    """One entry per stored text: its size, its hash, and what the text itself classifies as.

    Deterministic by construction: sorted by filename, no timestamps, nothing derived from the seal.
    The sealer writes the index before it seals, so the index cannot depend on the seal it is pinned
    inside - that would be a circle.
    """
    entries = []
    for path in sorted(store.glob("*")):
        if not path.is_file() or not STORE_FILE.match(path.name):
            continue
        data = path.read_bytes()
        verdict = license_text.classify(data.decode("utf-8", "replace"))
        entries.append({"file": path.name,
                        "record": path.name[:-len(SUFFIX)] if path.name.endswith(SUFFIX) else "",
                        "bytes": len(data),
                        "sha256": sha256_bytes(data),
                        "spdx": verdict["spdx"],
                        "blocked_by": verdict["blocked_by"],
                        "note": verdict["note"]})
    canonical = json.dumps(entries, sort_keys=True, separators=(",", ":"))
    return {"store": store.name,
            "texts": entries,
            "count": len(entries),
            "bytes": sum(entry["bytes"] for entry in entries),
            "index_sha256": sha256_bytes(canonical.encode("utf-8"))}


def write_index(index_path: Path = INDEX, store: Path = STORE) -> dict:
    index = build_index(store)
    index_path.write_text(json.dumps(index, indent=1) + "\n", encoding="utf-8")
    return index


def _stored(store: Path) -> dict[str, dict]:
    """Every file in the store, licensed-looking or not: a stray one is a problem, so it is seen."""
    return {path.name: {"bytes": path.stat().st_size, "sha256": sha256_bytes(path.read_bytes())}
            for path in sorted(store.glob("*")) if path.is_file()}


def verify(store: Path = STORE, index_path: Path = INDEX, seal_path: Path = SEAL_FILE) -> list[str]:
    """Every way the store, the index, the seal and the registries fail to agree. Empty means sound."""
    problems: list[str] = []
    present = _stored(store)
    for name in sorted(present):
        if not STORE_FILE.match(name):
            problems.append(f"the store holds {name}, which is not a licence text; the store holds "
                            f"exactly one text per record, named <id>{SUFFIX}")

    if not index_path.is_file():
        problems.append(f"there is no licence index at {shown(index_path)}; the store "
                        f"is not held together by anything")
    else:
        recorded = json.loads(index_path.read_text(encoding="utf-8"))
        current = build_index(store)
        if recorded.get("index_sha256") != current["index_sha256"]:
            problems.append("the licence index is stale: it was built from a different store than the "
                            "one on disk (run `index` and commit the result)")
        for entry in recorded.get("texts", []):
            name = entry.get("file", "")
            if name not in present:
                problems.append(f"the index records {name}, which is not in the store")
                continue
            if present[name]["sha256"] != entry.get("sha256"):
                problems.append(f"the stored text {name} is not the text the index records "
                                f"(index {entry.get('sha256', '')[:16]}, store "
                                f"{present[name]['sha256'][:16]})")
        indexed = {entry.get("file") for entry in recorded.get("texts", [])}
        for name in sorted(set(present) - indexed):
            if STORE_FILE.match(name):
                problems.append(f"the store holds {name}, which the index does not record")

    factories = []
    if seal_path.is_file():
        factories = json.loads(seal_path.read_text(encoding="utf-8")).get("factories", [])
    else:
        problems.append(f"there is no seal at {shown(seal_path)} to check the store "
                        f"against")
    referenced = set()
    for factory in factories:
        identifier = factory.get("id", "?")
        licence_file = factory.get("license_file")
        carried = bool(factory.get("content_redistributed"))
        if factory.get("license_present") and not licence_file:
            problems.append(f"{identifier} is marked as publishing a licence but names no file")
        if licence_file:
            referenced.add(licence_file)
            if licence_file not in present:
                problems.append(f"{identifier} names the licence text {licence_file}, which is not in "
                                f"the store")
        if carried and not licence_file:
            problems.append(f"{identifier} carries content with no licence text beside it: the seal "
                            f"must not redistribute content without the licence that allows it")
    for name in sorted(set(present) - referenced):
        problems.append(f"the store holds {name}, which no sealed record names (an orphan)")

    # The registries describe the set that is supposed to exist; the store has to cover what they
    # declare, or the growth run quietly shipped a set with a licence missing.
    for kind, registry_path, group in REGISTRIES:
        if not registry_path.is_file():
            continue
        for entry in entries_of(json.loads(registry_path.read_text(encoding="utf-8"))):
            identifier = entry.get("id", "")
            if not identifier:
                continue
            canonical = f"{identifier}{SUFFIX}"
            if canonical in present:
                continue
            record = _registry_records(group, identifier)
            if not wants_a_text(entry, record):
                continue
            if record is None:
                # Not this tool's problem to fix, but not silence either: the vendoring step owns it.
                problems.append(f"the {kind} registry names a licence for {identifier}, no text is "
                                f"stored, and there is no vendored record to import it into")
            elif record.get("_license_bytes"):
                problems.append(f"the {kind} registry names a licence for {identifier} and the "
                                f"vendored record carries the bytes, but no text is stored: run "
                                f"`import`")
            else:
                problems.append(f"the {kind} registry names a licence for {identifier} and neither "
                                f"the store nor the vendored record has it: run `import`")
    return problems


def wants_a_text(entry: dict, record: dict | None) -> bool:
    """Whether this record is supposed to have a licence text in the store.

    Read from what exists, not from a field that was assumed: the registries carry `license` and
    `license_path`, and the vendored record carries the fetched bytes (`_license_bytes`) and the
    upstream filename (`license_file`). A record whose vendored slice has neither, and whose registry
    names no licence, publishes no licence this component has seen - and no text is invented for it.
    """
    if record and (record.get("_license_bytes") or record.get("license_file")):
        return True
    return entry.get("license") not in (None, "", "NOASSERTION")


def _registry_records(group: str, identifier: str) -> dict | None:
    path = SEALED / group / f"{identifier}.json"
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except ValueError:
        return None


def _record_path(identifier: str) -> Path | None:
    for _kind, _registry_path, group in REGISTRIES:
        candidate = SEALED / group / f"{identifier}.json"
        if candidate.is_file():
            return candidate
    return None


def _store_bytes(identifier: str, data: bytes, store: Path, record_path: Path | None,
                 upstream: str = "") -> str:
    """Write one text into the store and into the vendored record the sealer reads it back from.

    Both, not either: the sealer writes `licenses/{id}-LICENSE` from the record's `_license_bytes` and
    prunes store files no record expects, so a text stored without the record would be deleted at the
    next seal. The record is updated only in the fields that describe the licence text - never
    `license`, never `content_redistributed`.
    """
    (store / f"{identifier}{SUFFIX}").write_bytes(data)
    if record_path is not None:
        record = json.loads(record_path.read_text(encoding="utf-8"))
        record["_license_bytes"] = base64.b64encode(data).decode()
        record["license_text_sha256"] = sha256_bytes(data)
        if upstream:
            record["license_file"] = upstream
        record_path.write_text(json.dumps(record, indent=1) + "\n", encoding="utf-8")
    return sha256_bytes(data)


def import_missing(store: Path = STORE, index_path: Path = INDEX, only: tuple[str, ...] = (),
                   dry_run: bool = False, fetch=license_rescan) -> tuple[list[dict], list[dict]]:
    """Fill the store's gaps. Returns (actions, problems) - problems are what import could not do.

    Order of preference is deliberate: a text the vendored record already carries costs nothing and
    needs no network, so the store is repaired from the component's own bytes first. Only where the
    bytes are absent too is the repository asked, through the same reader the licence re-scan uses.
    """
    actions: list[dict] = []
    problems: list[dict] = []
    for kind, registry_path, group in REGISTRIES:
        if not registry_path.is_file():
            continue
        for entry in entries_of(json.loads(registry_path.read_text(encoding="utf-8"))):
            identifier = entry.get("id", "")
            if not identifier or (only and identifier not in only):
                continue
            canonical = store / f"{identifier}{SUFFIX}"
            if canonical.is_file():
                actions.append({"id": identifier, "kind": kind, "action": "already-stored"})
                continue
            record_path = SEALED / group / f"{identifier}.json"
            record = _registry_records(group, identifier)
            if record is not None:
                encoded = record.get("_license_bytes")
                if encoded:
                    if not dry_run:
                        # No record argument: the bytes came from the record, so rewriting it would
                        # only re-encode what is already there.
                        _store_bytes(identifier, base64.b64decode(encoded), store, None)
                    actions.append({"id": identifier, "kind": kind, "action": "restored-from-record"})
                    continue
            if not wants_a_text(entry, record):
                actions.append({"id": identifier, "kind": kind, "action": "no-licence-published"})
                continue
            if record is None:
                actions.append({"id": identifier, "kind": kind, "action": "not-vendored"})
                continue
            if dry_run:
                actions.append({"id": identifier, "kind": kind, "action": "would-import"})
                continue
            full_name = entry.get("full_name", "")
            repo = fetch.gh_json(f"repos/{full_name}")
            if not repo:
                problems.append({"id": identifier, "action": "unreadable",
                                 "reason": "the repository could not be read (it may have moved or "
                                           "been deleted)"})
                continue
            branch = repo.get("default_branch") or "main"
            tree = fetch.gh_json(f"repos/{full_name}/git/trees/{branch}?recursive=1") or {}
            candidates = fetch.licence_candidates(tree.get("tree", []))
            if not candidates:
                actions.append({"id": identifier, "kind": kind, "action": "no-licence-published"})
                continue
            text, _blob = fetch.fetch_text(full_name, candidates[0])
            if not text.strip():
                problems.append({"id": identifier, "action": "unreadable",
                                 "reason": f"{candidates[0]} could not be read"})
                continue
            data = text.encode("utf-8")
            digest = _store_bytes(identifier, data, store, record_path, upstream=candidates[0])
            actions.append({"id": identifier, "kind": kind, "action": "imported",
                            "file": candidates[0], "bytes": len(data), "sha256": digest})

    changed = [action for action in actions if action["action"] in ("imported", "restored-from-record")]
    if changed and not dry_run:
        write_index(index_path, store)
    # A rate-limited or moved repository leaves a text nobody imported. `problems` is non-empty for
    # exactly those cases and the command exits non-zero on it: an incomplete store is not a clean one.
    return actions, problems


def describe(identifier: str, store: Path = STORE, index_path: Path = INDEX) -> dict:
    """What the store holds for one record, for `show` and for the self-check inside the seal."""
    canonical = store / f"{identifier}{SUFFIX}"
    answer = {"id": identifier, "file": canonical.name, "present": canonical.is_file()}
    if canonical.is_file():
        data = canonical.read_bytes()
        verdict = license_text.classify(data.decode("utf-8", "replace"))
        answer.update({"bytes": len(data), "sha256": sha256_bytes(data), "spdx": verdict["spdx"],
                       "blocked_by": verdict["blocked_by"], "note": verdict["note"]})
    if index_path.is_file():
        index = json.loads(index_path.read_text(encoding="utf-8"))
        entry = next((item for item in index.get("texts", []) if item.get("file") == canonical.name),
                     None)
        answer["indexed"] = entry is not None
    return answer


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Store and handle the sealed licence texts")
    parser.add_argument("command", choices=("index", "verify", "import", "show"))
    parser.add_argument("record", nargs="?", default="", help="the record id, for `show`")
    parser.add_argument("--store", default=str(STORE))
    parser.add_argument("--index", default=str(INDEX))
    parser.add_argument("--seal", default=str(SEAL_FILE))
    parser.add_argument("--only", default="", help="comma-separated record ids, for `import`")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv[1:])

    store = Path(args.store)
    index_path = Path(args.index)

    if args.command == "show":
        if not args.record:
            print("show needs a record id", file=sys.stderr)
            return 2
        print(json.dumps(describe(args.record, store, index_path), indent=1))
        return 0

    if args.command == "index":
        index = write_index(index_path, store)
        print(f"licence index: {index['count']} texts, {index['bytes']} bytes, "
              f"digest {index['index_sha256'][:16]}… -> {index_path}")
        return 0

    if args.command == "verify":
        problems = verify(store, index_path, Path(args.seal))
        for problem in problems:
            print(f"licence store: {problem}", file=sys.stderr)
            print(f"::error title=Licence store::{problem}")
        if problems:
            print(f"licence store: {len(problems)} problem(s); the store does not agree with the set",
                  file=sys.stderr)
            return 1
        index = json.loads(index_path.read_text(encoding="utf-8"))
        print(f"licence store: {index['count']} texts verified against the index, the seal and the "
              f"registries")
        return 0

    only = tuple(part.strip() for part in args.only.split(",") if part.strip())
    actions, problems = import_missing(store, index_path, only=only, dry_run=args.dry_run)
    counts: dict[str, int] = {}
    for action in actions:
        counts[action["action"]] = counts.get(action["action"], 0) + 1
    for action in actions:
        if action["action"] in ("imported", "restored-from-record"):
            print(f"licence store: {action['action']} {action['id']} "
                  f"({action.get('file') or 'from the vendored record'})")
    for problem in problems:
        print(f"licence store: {problem['action']} {problem['id']}: {problem['reason']}",
              file=sys.stderr)
    if args.json:
        print(json.dumps({"actions": actions, "problems": problems}, indent=1))
    else:
        print(f"licence store: import summary {counts or 'nothing to do'}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
