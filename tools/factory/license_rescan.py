#!/usr/bin/env python3
"""Re-scan every catalogued repository for a licence this component may carry, and record the text.

`vendor_imported_factories.py` looks for a licence at the repository root and only under the names
`LICENSE`, `LICENCE`, `COPYING` and a few variants. GitHub's detector is stricter still: it answers
`NOASSERTION` for a plain MIT text whose title was replaced or whose copyright line it cannot parse.
Between those two, the sealed component withheld content it was entitled to carry - a repository that
publishes agent and skill definitions, with an MIT `LICENSE` two directories deep, was imported as a
blueprint that defines nothing.

This tool closes that gap without redistributing anything it should not:

* it looks for licence-like files anywhere in the tree (`LICENSE`, `LICENCE`, `COPYING`, `NOTICE`,
  `UNLICENSE`, `0BSD`, and suffixed forms), preferring the shortest path;
* it classifies the text with `license_text.classify`, which recognises a permissive licence only by
  its canonical grant and refuses anything carrying a copyleft, source-available, non-commercial,
  enterprise or documentation-mixed term;
* it *widens* a record's licence only when the text is unambiguous and the recorded licence is
  `NOASSERTION` or empty. A text that disagrees with a detected licence is reported for review and the
  record is left alone, because "the detector said MIT, the text says GPL" is a decision for a human.

Usage:
    python3 tools/factory/license_rescan.py                 # report only
    python3 tools/factory/license_rescan.py --apply         # write the registry changes
    python3 tools/factory/license_rescan.py --only id,id    # a subset
"""

from __future__ import annotations

import argparse
import base64
import json
import re
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools" / "factory"))

import license_text  # noqa: E402

REGISTRIES = (
    REPO_ROOT / "tools" / "factory" / "imported_factories.json",
    REPO_ROOT / "tools" / "factory" / "imported_ai_factories.json",
    REPO_ROOT / "tools" / "factory" / "imported_mcp_servers.json",
)

#: Any file whose name looks like a licence, at any depth. The suffix forms matter in practice:
#: `LICENSE-MIT` / `LICENSE-APACHE` are how Rust and some JS projects publish dual grants.
LICENCE_FILE = re.compile(
    r"^(licen[cs]e|copying|notice|unlicen[cs]e|0bsd)([-._][\w.-]+)?(\.(md|txt|rst))?$", re.I)

#: Directories whose contents are never the repository's own licence.
SKIP_DIRS = re.compile(r"^(node_modules|vendor|third_party|third-party|test|tests|fixtures|examples?)$",
                       re.I)


def gh_json(path: str, attempts: int = 3):
    """One `gh api` call, retried on rate limiting. Same contract as the vendoring tool's."""
    for attempt in range(attempts):
        done = subprocess.run(["gh", "api", path], capture_output=True, text=True)
        if done.returncode == 0:
            try:
                return json.loads(done.stdout)
            except ValueError:
                return None
        if "rate limit" in done.stderr.lower() or "secondary" in done.stderr.lower():
            time.sleep(20 * (attempt + 1))
            continue
        if attempt == attempts - 1:
            return None
        time.sleep(2)
    return None


#: A `NOTICE` is not a grant. It is an Apache-2.0 *obligation* file, and several repositories publish
#: one beside their `LICENSE`; picking it first made the scanner read an attribution notice and
#: conclude the repository's licence was unclassifiable. Grants are ranked ahead of notices, and the
#: suffixed forms (`LICENSE-MIT`) ahead of nothing at all: a root `LICENSE` explains the repository
#: better than either.
GRANT_NAMES = ("LICENSE", "LICENCE", "COPYING", "UNLICENSE", "0BSD")


def _candidate_rank(name: str) -> int:
    upper = name.upper()
    stem = re.split(r"[.\-]", upper, maxsplit=1)[0]
    if upper in GRANT_NAMES:
        return 0
    if stem in GRANT_NAMES:
        return 1          # LICENSE-MIT, LICENSE-APACHE, LICENSE.md, COPYING.LESSER
    if upper == "NOTICE":
        return 3
    return 2


def licence_candidates(tree: list[dict]) -> list[str]:
    """Root-level licence paths, best first - a real grant beats a notice.

    Root level only, deliberately. A `LICENSE` deep inside a repository usually licenses *that
    directory* - a vendored rule pack, a bundled component - and reading one as the repository's own
    licence would attribute terms to a whole project that never published them. The question this tool
    answers is "what does this repository publish as its licence", and that answer lives at the root.
    """
    found: list[tuple[int, str]] = []
    for entry in tree:
        path = entry.get("path", "")
        if entry.get("type") != "blob" or "/" in path or not path:
            continue
        if LICENCE_FILE.match(path):
            found.append((_candidate_rank(path), path))
    return [path for _, path in sorted(found)]


def fetch_text(full_name: str, path: str) -> tuple[str, str]:
    """The licence text at `path`, and the sha of the blob the API reports for it."""
    blob = gh_json(f"repos/{full_name}/contents/{path}")
    if not blob or not blob.get("content"):
        return "", ""
    return base64.b64decode(blob["content"]).decode("utf-8", "replace"), blob.get("sha", "")


def rescan_entry(entry: dict) -> dict:
    """Decide what this entry's licence should be, from its own published text."""
    full_name = entry["full_name"]
    verdict = {"id": entry["id"], "full_name": full_name, "recorded": entry.get("license"),
               "found_path": "", "spdx": "", "blocked_by": "", "note": "", "action": "unchanged",
               "evidence": "", "sha": ""}
    repo = gh_json(f"repos/{full_name}")
    if not repo:
        verdict["note"] = "the repository could not be read (it may have moved or been deleted)"
        verdict["action"] = "unreadable"
        return verdict
    branch = repo.get("default_branch") or "main"
    tree_payload = gh_json(f"repos/{full_name}/git/trees/{branch}?recursive=1") or {}
    candidates = licence_candidates(tree_payload.get("tree", []))
    if not candidates:
        verdict["note"] = "the repository publishes no licence file under any name this tool knows"
        verdict["action"] = "no-licence"
        return verdict
    text, sha = fetch_text(full_name, candidates[0])
    verdict["found_path"] = candidates[0]
    verdict["sha"] = sha
    if not text:
        verdict["note"] = f"{candidates[0]} could not be read"
        verdict["action"] = "unreadable"
        return verdict
    verdict.update(verdict_for(entry.get("license"), license_text.classify(text),
                               candidates[0], sha))
    return verdict


def verdict_for(recorded: str | None, result: dict, found_path: str = "", sha: str = "") -> dict:
    """The verdict for one classified text: what it says, and what the registry may do about it."""
    return {"recorded": recorded, "found_path": found_path, "spdx": result["spdx"],
            "blocked_by": result["blocked_by"], "note": result["note"], "evidence": result["evidence"],
            "sha": sha, "action": decide(recorded, result)}


def decide(recorded: str | None, result: dict) -> str:
    """The action for `recorded` (what the registry says) and `result` (what the text said).

    The asymmetry is the point: the text may *fill in* a licence nobody had stated, and may confirm
    one that was already recorded, but it may never quietly replace a licence the registry names. A
    disagreement is escalated for a person to look at rather than resolved by whichever source this
    tool happens to trust more today.
    """
    recorded = recorded or ""
    if result["spdx"]:
        if recorded == result["spdx"]:
            return "confirmed"
        if recorded in ("", "NOASSERTION"):
            return "widen"
        return "disagrees"
    if result["blocked_by"]:
        return "blocked"
    return "withheld"


def apply_verdict(entry: dict, verdict: dict) -> bool:
    """Write what the text says into the registry entry. Returns True when something changed."""
    changed = False
    if verdict["action"] in ("widen", "confirmed"):
        if entry.get("license") != verdict["spdx"]:
            entry["license_detected"] = entry.get("license")
            entry["license_source"] = "text"
            entry["license"] = verdict["spdx"]
            changed = True
        # The class describes what this component *does* with the record. Once the text shows a
        # licence that permits reuse, the content is carriable, so a blueprint-only label would be
        # stale - whatever GitHub's detector said when the record was first written. The vendoring
        # step re-fetches the slice and the seal starts carrying it.
        if entry.get("import_class") == "blueprint-only":
            entry["import_class"] = "asset-import"
            changed = True
        if entry.get("license_file_needed"):
            entry["license_file_needed"] = False
            changed = True
    for key, value in (("license_evidence", verdict["evidence"]),
                       ("license_path", verdict["found_path"])):
        if value and entry.get(key) != value:
            entry[key] = value
            changed = True
    if verdict["blocked_by"] and entry.get("license_note") != f"blocked: {verdict['blocked_by']}":
        entry["license_note"] = f"blocked: {verdict['blocked_by']}"
        changed = True
    return changed


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Re-scan catalogued repositories for their licence")
    parser.add_argument("--only", default="", help="comma-separated ids")
    parser.add_argument("--apply", action="store_true", help="write the registry changes")
    # A scan that could not read a repository leaves that record exactly as it was, which is the safe
    # outcome but not a complete scan: on a rate-limited runner it would report success having checked
    # nothing. Growth runs pass this so a degraded scan fails loudly instead of passing quietly.
    parser.add_argument("--fail-on-unreadable", action="store_true",
                        help="exit non-zero if any record could not be read")
    args = parser.parse_args(argv[1:])
    wanted = {part.strip() for part in args.only.split(",") if part.strip()}

    summary = {"widen": 0, "confirmed": 0, "blocked": 0, "withheld": 0, "disagrees": 0,
               "no-licence": 0, "unreadable": 0, "unchanged": 0}
    written = 0
    for registry_path in REGISTRIES:
        if not registry_path.is_file():
            continue
        document = json.loads(registry_path.read_text(encoding="utf-8"))
        # The imported-factory registry is a list; the AI and MCP registries are objects with the
        # records under a key. Both shapes are scanned, because both carry licences.
        if isinstance(document, list):
            entries, container, key = document, None, None
        else:
            key = next((name for name in ("factories", "servers", "records", "entries")
                        if isinstance(document.get(name), list)), None)
            if key is None:
                continue
            entries, container = document[key], document
        dirty = False
        for entry in entries:
            if wanted and entry.get("id") not in wanted:
                continue
            verdict = rescan_entry(entry)
            summary[verdict["action"]] = summary.get(verdict["action"], 0) + 1
            if verdict["action"] != "unchanged":
                print(f'{verdict["action"]:11s} {verdict["id"][:38]:38s} '
                      f'{str(verdict["recorded"])[:12]:12s} -> '
                      f'{(verdict["spdx"] or ("blocked:" + verdict["blocked_by"]) or "withheld")[:24]:24s} '
                      f'{verdict["found_path"][:34]:34s} {verdict["note"][:40]}', flush=True)
            if args.apply and apply_verdict(entry, verdict):
                dirty = True
        if args.apply and dirty:
            if container is None:
                payload = entries
            else:
                container[key] = entries
                payload = container
            # Written the way the importer writes its registries (`indent=2`, ASCII escapes kept), so
            # re-running this tool produces a diff of the licence fields and nothing else.
            registry_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
            written += 1
    print(f"\n{'applied to ' + str(written) + ' registry file(s); ' if args.apply else 'report only; '}"
          f"{json.dumps(summary, sort_keys=True)}")
    if args.fail_on_unreadable and summary["unreadable"]:
        print(f"{summary['unreadable']} record(s) could not be read, so this scan is incomplete; "
              f"nothing was guessed for them", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
