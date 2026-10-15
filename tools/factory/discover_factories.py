#!/usr/bin/env python3
"""Find more factories on GitHub and add them to the imported-factory registry.

The sealed set is only as operational as the repositories behind it: 22 of its records publish no
licence at all, so nothing of theirs may be carried, and a record that carries nothing defines nothing.
The fix is not to argue with those licences - it is to import more repositories that *can* be carried
and that bring real definitions with them.

This tool does that search, and it does it the same way the original 72 were found, so the method stays
reproducible:

1. GitHub code-and-metadata search over the phrases this project implements (staged production with AI
   workers), then a licence filter - only licences this component may redistribute, or CC0;
2. each candidate's **actual tree** is read through the GitHub API and scored against the same six
   characteristics the catalogue uses: worker/agent code, stages, orchestration, factory naming,
   verification/review, build/test/ship steps. Four or more are required, and the evidence recorded is
   the paths that showed them - not a claim;
3. entries are appended to `tools/factory/imported_factories.json` and rows to
   `docs/factory/IMPORTED_FACTORY_CATALOG.md`, the pair `shell/tests/test_imported_factories.py`
   requires to agree. Nothing is vendored here; that is `vendor_imported_factories.py --only <ids>`.

Usage:
    python3 tools/factory/discover_factories.py --query '"agent skills" in:name,description' --limit 12
    python3 tools/factory/discover_factories.py --query ... --apply
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REGISTRY = REPO_ROOT / "tools" / "factory" / "imported_factories.json"
CATALOG = REPO_ROOT / "docs" / "factory" / "IMPORTED_FACTORY_CATALOG.md"

PERMISSIVE_LICENCES = {"MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "CC0-1.0",
                       "Unlicense", "0BSD"}

#: GitHub returns these upper-cased (`APACHE-2.0`, `AGPL-3.0`, `BSD-3-CLAUSE`), so every comparison
#: goes through the lower-cased table and the canonical spelling is what gets stored.
CANONICAL = {licence.lower(): licence for licence in PERMISSIVE_LICENCES
             | {"AGPL-3.0", "GPL-3.0", "GPL-2.0", "LGPL-3.0", "Elastic-2.0", "PolyForm-Shield-1.0.0",
                "BSL-1.0", "SSPL-1.0", "MPL-2.0", "CC-BY-4.0", "CC-BY-SA-4.0", "NOASSERTION"}}


def canonical_licence(raw: str) -> str:
    return CANONICAL.get((raw or "").lower(), (raw or "").upper() or "NOASSERTION")

#: The six characteristics, and the tree shapes that demonstrate each one. A characteristic counts only
#: when a real path proves it, which is why every entry can list the files behind its evidence.
CHARACTERISTICS: tuple[tuple[str, re.Pattern], ...] = (
    ("worker/agent code", re.compile(r"(^|/)(agents?|workers?|subagents?|personas?|roles?)/", re.I)),
    ("stages", re.compile(r"(^|/)(stages?|steps?|phases?|pipelines?|workflows?)/", re.I)),
    ("orchestration", re.compile(r"(orchestrat\w*|conductor|coordinator|scheduler|runner|dispatcher|"
                                 r"supervisor)", re.I)),
    ("factory naming", re.compile(r"factory", re.I)),
    ("verification/review", re.compile(r"(verify|verifier|review\w*|critic|check\w*|qa|quality)", re.I)),
    ("build/test/ship steps", re.compile(r"(^|/)(\.github/workflows/|\.gitlab-ci|Jenkinsfile|"
                                         r"buildspecs?/|Makefile|justfile|Taskfile)", re.I)),
)

#: A path that carries a *definition* - somewhere a stage, step, pipeline or workflow is described.
#: A repository may use the word "steps" in a utility module and still define nothing; the scan has to
#: be able to tell those apart, so a candidate needs at least one path from this set or from
#: `AI_WORKER_PATH` before it can be registered.
STAGE_PATH = re.compile(r"(^|/)(stages?|steps?|phases?|pipelines?|workflows?)/", re.I)

#: CI configuration describes how a repository is *built*, which every repository has. It is a use of a
#: build step, not a definition of a factory stage, so it never counts as a definition on its own -
#: otherwise every project with a workflow file would look like a factory.
CI_ONLY = re.compile(r"^(\.github/workflows/|\.gitlab-ci|Jenkinsfile|\.circleci/|\.travis|"
                     r"\.woodpecker|azure-pipelines)", re.I)

#: A path is AI-worker evidence when it is somewhere an AI worker is defined rather than merely used.
AI_WORKER_PATH = re.compile(
    r"(^|/)(agents?|subagents?|workers?|prompts?|prompt_templates?|skills?|personas?|crews?|swarms?|"
    r"\.claude|\.agent|\.ai|instructions?)/", re.I)

#: Noise the original sweep excluded by hand; keep excluding it, by rule.
NOISE = re.compile(r"(awesome-|tutorial|course|bootcamp|cheat[-_]?sheet|interview)", re.I)


def gh_json(path: str, attempts: int = 3):
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


def characteristics(paths: list[str]) -> list[str]:
    """The characteristics this repository's own tree demonstrates, with the proving paths kept."""
    found: list[str] = []
    for name, pattern in CHARACTERISTICS:
        if any(pattern.search(path) for path in paths):
            found.append(name)
    return found


def evidence_paths(paths: list[str], limit: int = 3) -> list[str]:
    """A few of the paths that carry the definition, for the registry entry's own record."""
    interesting = [path for path in paths if AI_WORKER_PATH.search(path) or STAGE_PATH.search(path)]
    return sorted(interesting, key=lambda path: (path.count("/"), len(path)))[:limit]


def slug(full_name: str) -> str:
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", full_name.lower())).strip("-")


def evaluate_tree(paths: list[str]) -> dict | None:
    """What a repository's own tree shows, or None when it is not factory-shaped.

    Four characteristics are the bar the catalogued set was built on. On top of that, at least one path
    has to be a *definition* - an agent, worker, prompt, skill, stage, step or pipeline. Without that
    second check a library whose utility module happens to be called `steps.py` looks like a factory and
    gets sealed as one, carrying files the runtime reads nothing from.
    """
    found = characteristics(paths)
    if len(found) < 4:
        return None
    definitions = [path for path in paths
                   if AI_WORKER_PATH.search(path)
                   or (STAGE_PATH.search(path) and not CI_ONLY.search(path))]
    if not definitions:
        return None
    return {"evidence": found, "evidence_count": len(found), "definitions": definitions[:3],
            "definition_count": len(definitions),
            "ai_worker_evidence": any(AI_WORKER_PATH.search(path) for path in paths)}


def candidate(entry: dict, query: str, snapshot: str,
              allow_non_permissive: bool = False) -> dict | None:
    """Turn one search hit into a registry entry, or return None with the reason printed.

    A sweep only ever adds what this component may carry, so a copyleft licence ends the candidate.
    A named plan is different: the tool is wanted for what it does, and a copyleft one is recorded as
    a blueprint - no content copied, `license_file_needed` set - which is the registry's own convention.
    """
    full_name = entry["full_name"]
    if entry.get("archived") or entry.get("disabled"):
        return None
    if NOISE.search(full_name) or NOISE.search(entry.get("description") or ""):
        return None
    licence = canonical_licence(((entry.get("license") or {}).get("spdx_id") or ""))
    carriable = licence in PERMISSIVE_LICENCES
    if not carriable and not allow_non_permissive:
        return None
    branch = entry.get("default_branch") or "main"
    tree_payload = gh_json(f"repos/{full_name}/git/trees/{branch}?recursive=1") or {}
    paths = [item["path"] for item in tree_payload.get("tree", []) if item.get("type") == "blob"]
    if not paths:
        return None
    shape = evaluate_tree(paths)
    if not shape:
        return None
    dirs = sorted({path.split("/")[0] for path in paths if "/" in path})[:24]
    return {
        "full_name": full_name,
        "url": entry["html_url"],
        "description": (entry.get("description") or "")[:240],
        "stars": entry.get("stargazers_count", 0),
        "forks": entry.get("forks_count", 0),
        "language": entry.get("language") or "",
        "size_kb": entry.get("size", 0),
        "pushed_at": entry.get("pushed_at"),
        "created_at": entry.get("created_at"),
        "license": licence,
        "topics": entry.get("topics") or [],
        "archived": False,
        "open_issues": entry.get("open_issues_count", 0),
        "queries": [query],
        "dirs": dirs,
        "entry_names": sorted({path.split("/")[0] for path in paths})[:24],
        "definition_paths": shape["definitions"],
        "evidence": shape["evidence"],
        "evidence_count": shape["evidence_count"],
        "ai_worker_evidence": shape["ai_worker_evidence"],
        "license_file_needed": not carriable,
        "id": slug(full_name),
        "import_class": "asset-import" if carriable else "blueprint-only",
        "snapshot": snapshot,
        "license_source": "api",
    }


def catalog_row(candidate_entry: dict) -> str:
    """One table row in the catalogue's own shape."""
    entry = candidate_entry
    description = (entry["description"] or "").replace("|", "\\|")[:120]
    workers = "yes" if entry["ai_worker_evidence"] else "no"
    return (f'| `{entry["id"]}` | [{entry["full_name"]}]({entry["url"]}) | {entry["stars"]} | '
            f'{entry["license"]} | {entry["import_class"]} | {workers} | '
            f'{", ".join(entry["evidence"])} | {description} |')


def write_catalog(text: str, registry: list[dict]) -> str:
    """Rebuild the catalog's factory table from the registry, and refresh the counts the prose states.

    Rebuilt rather than appended to. Two earlier versions of this function appended rows, and both
    were wrong in ways that only showed up later: rows landed in the *class* table above (its cells also
    begin with a backticked word, so "the first table that looks like this" was the wrong table), and a
    repeated run appended the same factory again. Rebuilding from the registry makes the document a
    projection of the registry, so drift and duplication are not possible by construction.
    """
    lines = text.split("\n")
    header = next(index for index, line in enumerate(lines)
                  if line.startswith("| id") and "repository" in line)
    start = header + 2                                   # the header, then the separator row
    end = start
    while end < len(lines) and lines[end].startswith("|"):
        end += 1
    rows = sorted((catalog_row(entry) for entry in registry),
                  key=lambda line: int(re.search(r"\| (\d+) \|", line).group(1)), reverse=True)
    lines[start:end] = rows

    # Any factory row that ended up outside that table (an older run's mistake) is removed.
    factory_row = re.compile(r"^\|\s*`([\w.-]+)`\s*\|\s*\[")
    lines = [line for index, line in enumerate(lines)
             if not (factory_row.match(line) and not (start <= index < start + len(rows)))]
    # Recompute `start` after the removal, then re-insert the table body in one place.
    header = next(index for index, line in enumerate(lines)
                  if line.startswith("| id") and "repository" in line)
    start = header + 2
    end = start
    while end < len(lines) and lines[end].startswith("|"):
        end += 1
    if end != start + len(rows):
        lines[start:end] = rows
    text = "\n".join(lines)

    asset = sum(1 for entry in registry if entry["import_class"] == "asset-import")
    blueprint = sum(1 for entry in registry if entry["import_class"] == "blueprint-only")
    workers = sum(1 for entry in registry if entry["ai_worker_evidence"])
    text = re.sub(r"(The result: \*\*)\d+( verified factories\*\*, of which \*\*)\d+",
                  rf"\g<1>{len(registry)}\g<2>{workers}", text)
    # The count is the last cell of the class table's row; the row's other cells are matched whole,
    # because the Meaning cell contains no pipe but does contain other numbers.
    for label, value in (("asset-import", asset), ("blueprint-only", blueprint)):
        text = re.sub(rf"(\| `{label}`\s*\|[^|]*\|\s*)\d+(\s*\|)",
                      rf"\g<1>{value}\g<2>", text)
    return text


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Discover and register factory repositories")
    parser.add_argument("--query", action="append", default=[],
                        help="a GitHub search query; repeatable")
    parser.add_argument("--from-plan", dest="from_plan",
                        help="repositories.json-style file whose `planned` list to import by name")
    parser.add_argument("--limit", type=int, default=8, help="how many to add")
    parser.add_argument("--min-stars", type=int, default=25)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv[1:])
    if not args.query and not args.from_plan:
        parser.error("give at least one --query or a --from-plan file")
    snapshot = time.strftime("%Y-%m-%d", time.gmtime())

    registry = json.loads(REGISTRY.read_text(encoding="utf-8"))
    known = {entry["full_name"].lower() for entry in registry}
    known_ids = {entry["id"] for entry in registry}
    added: list[dict] = []
    for query in args.query:
        if len(added) >= args.limit:
            break
        search = gh_json(f"search/repositories?q={query.replace(' ', '+')}&sort=stars&per_page=30")
        for entry in (search or {}).get("items", []):
            if len(added) >= args.limit:
                break
            if entry["full_name"].lower() in known or entry.get("stargazers_count", 0) < args.min_stars:
                continue
            built = candidate(entry, query, snapshot)
            if not built or built["id"] in known_ids:
                continue
            known.add(built["full_name"].lower())
            known_ids.add(built["id"])
            added.append(built)
            print(f'+ {built["id"]:44s} {built["license"]:12s} '
                  f'{"AI-worker " if built["ai_worker_evidence"] else "          "}'
                  f'{built["stars"]:5d}★ {", ".join(built["evidence"])}')

    outcomes: list[dict] = []
    if args.from_plan:
        plan = json.loads(Path(args.from_plan).read_text(encoding="utf-8"))
        for item in plan.get("planned", []):
            url = item.get("repository", "")
            match = re.match(r"https://github\.com/([^/]+/[^/#]+)", url)
            if not match:
                reason = (f'host is not github.com ({url}); the registry and its tests address '
                          f'records as https://github.com/<full_name>')
                print(f'= {item.get("tool", "?"):16s} refused: {reason}')
                outcomes.append({"tool": item.get("tool"), "repository": url, "outcome": "refused",
                                 "reason": reason})
                continue
            full_name = match.group(1)
            if full_name.lower() in known:
                # Imported by an earlier pass (an `--apply` sweep, or this file run twice). It still
                # belongs in the record - the point of the file is that no named repository is
                # unaccounted for, not that this particular run is what added it.
                existing = next((entry for entry in registry
                                 if entry["full_name"].lower() == full_name.lower()), {})
                outcomes.append({"tool": item.get("tool"), "repository": url, "outcome": "imported",
                                 "id": existing.get("id"), "license": existing.get("license"),
                                 "import_class": existing.get("import_class"),
                                 "reason": "already in the registry from an earlier pass"})
                print(f'= {item.get("tool", "?"):16s} already imported as {existing.get("id")}')
                continue
            if len(added) >= args.limit:
                continue
            meta = gh_json(f"repos/{full_name}")
            if not meta or meta.get("message"):
                reason = "repository metadata unreadable through the GitHub API"
                print(f'= {item.get("tool", "?"):16s} refused: {reason}')
                outcomes.append({"tool": item.get("tool"), "repository": url, "outcome": "refused",
                                 "reason": reason})
                continue
            built = candidate(meta, f'planned:{item.get("tool", "")}', snapshot,
                              allow_non_permissive=True)
            if not built:
                reason = ("its own tree shows neither four of the six factory characteristics nor a "
                          "single definition path (an agent, worker, prompt, skill, stage, step or "
                          "pipeline) - CI configuration alone does not make a factory")
                print(f'= {item.get("tool", "?"):16s} refused: {reason}')
                outcomes.append({"tool": item.get("tool"), "repository": url, "outcome": "refused",
                                 "reason": reason})
                continue
            built["planned_tool"] = item.get("tool", "")
            built["handles"] = item.get("handles", "")
            built["plan_gap"] = item.get("gap", "")
            known.add(built["full_name"].lower())
            known_ids.add(built["id"])
            added.append(built)
            print(f'+ {built["id"]:44s} {built["license"]:12s} {built["import_class"]:14s} '
                  f'{built["stars"]:5d}* {", ".join(built["evidence"])}')
            outcomes.append({"tool": item.get("tool"), "repository": url, "outcome": "imported",
                             "id": built["id"], "license": built["license"],
                             "import_class": built["import_class"],
                             "reason": f'imported as {built["import_class"]} under {built["license"]}'})

    print(f"\n{len(added)} new factory record(s)")
    if not args.apply:
        print("report only; pass --apply to write the registry and the catalog")
        return 0

    # Registry first, then the table row that has to agree with it, then the counts the prose states.
    # The counts are refreshed even when this run added nothing, so a licence re-classified elsewhere
    # cannot leave the catalog describing a set it no longer matches.
    if added:
        registry.extend(added)
        REGISTRY.write_text(json.dumps(registry, indent=2) + "\n", encoding="utf-8")

    text = CATALOG.read_text(encoding="utf-8")
    text = write_catalog(text, registry)

    CATALOG.write_text(text, encoding="utf-8")
    asset = sum(1 for entry in registry if entry["import_class"] == "asset-import")
    blueprint = sum(1 for entry in registry if entry["import_class"] == "blueprint-only")
    workers = sum(1 for entry in registry if entry["ai_worker_evidence"])

    if outcomes:
        plan_path = Path(args.from_plan)
        report_path = plan_path.with_name("plan_import.json")
        report_path.write_text(json.dumps(
            {"method": ("every repository the plan names is either imported into "
                        "imported_factories.json or refused with the reason recorded here"),
             "checked": snapshot, "plan": plan_path.name, "outcomes": outcomes},
            indent=2) + "\n", encoding="utf-8")
        refused = sum(1 for entry in outcomes if entry["outcome"] == "refused")
        print(f"plan: {len(outcomes)} named, {len(outcomes) - refused} imported, {refused} refused "
              f"-> {report_path}")
    print(f"registry: {len(registry)} entries ({asset} asset-import, {blueprint} blueprint-only, "
          f"{workers} with AI-worker evidence)")
    print("catalog: rows and counts updated")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
