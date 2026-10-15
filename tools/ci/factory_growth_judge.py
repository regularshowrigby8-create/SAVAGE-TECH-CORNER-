#!/usr/bin/env python3
"""Judge the GitHub-native factory-growth run against the ledger the committed set has reached.

`tools/ci/factory_growth.sh` re-reads every catalogued repository's licence, imports further factories
from GitHub, re-vendors what changed, re-seals the set, runs the sealed runtime in plan mode, and then
calls this module. What it decides:

- the number of sealed records, the number of imported records, the number that read as operational and
  the number whose content is carried may grow and may not fall below the ledger below. A fall is a
  regression in what the set can do - content being stripped, a definition disappearing upstream - and
  the judge is what stops a run from reporting one as if it were fine. Moving a floor down is therefore
  a deliberate edit to this file, in a commit a person writes;
- content is carried only under a licence `tools/factory/seal_factories.py` accepts;
- every record is either operational or content-free. Two records are allowed to sit in neither state,
  because both carry a README and define nothing on purpose: `awesome-mcp-servers` (a curated list, with
  no launch command) and `github-mcp-server` (MIT text carried, but its Go binary could not be fetched).
  The run reports them by name and this judge refuses to let any *other* record join them;
- the run's own summary has to agree with the records it lists.

The judge lives in its own file, rather than inside the shell script, so the tests can hand it synthetic
sets - including the ones that must fail - instead of trusting a gate that has only ever been observed
to pass.

Usage: factory_growth_judge.py <RUN-REPORT.json> <SEAL.json> <imported_factories.json>
Prints what it found and exits 0, or prints every reason it rejected the run and exits 1.
"""

from __future__ import annotations

import collections
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools" / "factory"))

import seal_factories  # noqa: E402  (the licence policy lives with the sealer, not here)

#: What the committed set has already reached, measured from `sealed-factories/SEAL.json` at root
#: `3b7d58ab1ae274ef63a3a5a2aabe0c2039d658d89de8c311e1a9420736ce7bea` and the `RUN-REPORT.json` of
#: the run that followed it (2026-10-15). These are floors, not targets: growth may exceed them and
#: must never fall below them.
LEDGER = {
    "records": 149,
    "imported_records": 84,
    "ai_records": 23,
    "mcp_records": 42,
    "operational_plan": 112,
    "carried_content": 113,
    "licence_texts": 127,
    "stages_defined": 592,
    "workers_defined": 579,
}

#: Records that carry a published README, define no stages, workers, gates or prompts, and are kept
#: anyway - the run names them in `carried_without_definitions` and in its own log. Anything else in
#: that state is a gap: it was sealed without ever being able to run.
REFERENCE_RECORDS = ("awesome-mcp-servers", "github-mcp-server")


def judge(report_path: Path, seal_path: Path, registry_path: Path) -> tuple[list[str], list[str], list[str]]:
    """Returns (problems, warnings, evidence). Empty problems means the grown set is accepted."""
    problems: list[str] = []
    warnings: list[str] = []
    evidence: list[str] = []

    def load(path: Path, what: str):
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as problem:
            problems.append(f"the {what} at {path} could not be read as JSON: {problem}")
            return None

    report = load(report_path, "run report")
    seal = load(seal_path, "seal")
    registry = load(registry_path, "imported-factory registry")
    if report is None or seal is None or registry is None:
        return problems, warnings, evidence
    if not isinstance(registry, list):
        problems.append(f"{registry_path} is not the list of records the sealer reads")
        return problems, warnings, evidence

    seal_factories_list = seal.get("factories") or []
    report_factories = report.get("factories") or []
    summary = report.get("summary") or {}
    contents = seal.get("contents") or {}
    records = contents.get("factories")
    operational = summary.get("operational_plan")

    # 1. The floors. The set may grow; it may not shrink.
    if records is None:
        problems.append("the seal records no factory count, so nothing below can be compared")
    elif records < LEDGER["records"]:
        problems.append(f"the seal holds {records} factories, below the {LEDGER['records']} the "
                        f"committed set already holds")
    if len(registry) < LEDGER["imported_records"]:
        problems.append(f"the registry lists {len(registry)} imported factories, below the "
                        f"{LEDGER['imported_records']} the committed registry already holds")
    if operational is None:
        problems.append("the run report carries no operational_plan count")
    elif operational < LEDGER["operational_plan"]:
        problems.append(f"{operational} factories read as operational in this run, below the "
                        f"{LEDGER['operational_plan']} the committed set already had")
    carried = contents.get("content_redistributed")
    if carried is None:
        problems.append("the seal does not say how many records carry their content")
    elif carried < LEDGER["carried_content"]:
        problems.append(f"the seal carries content for {carried} records, below the "
                        f"{LEDGER['carried_content']} the committed set already carried; content was "
                        f"stripped, which is a change in what the set can do and not a grown set")

    # 2. The seal's own count has to be the count of the records beside it: both are written by the
    #    sealer, so a mismatch means the document was edited by hand rather than produced.
    recorded_carried = sum(1 for factory in seal_factories_list if factory.get("content_redistributed"))
    if carried is not None and carried != recorded_carried:
        problems.append(f"the seal counts {carried} records carrying content while it lists "
                        f"{recorded_carried}")

    # 3. The seal and the run have to describe the same factories.
    seal_ids = {factory.get("id") for factory in seal_factories_list}
    report_ids = {factory.get("id") for factory in report_factories}
    if seal_ids != report_ids:
        only_sealed = sorted(seal_ids - report_ids)
        only_reported = sorted(report_ids - seal_ids)
        problems.append(f"the seal and the run disagree on the set: sealed only {only_sealed[:5]}, "
                        f"reported only {only_reported[:5]}")

    # 4. Every imported record has a slice, and every slice belongs to a record: a registry entry with
    #    no slice is silently absent from the seal, and a stale slice is silently present in it.
    groups = contents.get("by_group") or {}
    imported_group = groups.get("imported factory")
    if imported_group is None:
        problems.append("the seal does not say how many imported factories it holds")
    elif imported_group != len(registry):
        problems.append(f"the seal holds {imported_group} imported factories while the registry lists "
                        f"{len(registry)}: a record with no slice, or a slice with no record")
    if groups and records is not None and sum(groups.values()) != records:
        problems.append(f"the seal's groups sum to {sum(groups.values())} of {records} factories")

    # 5. The summary has to agree with the records it summarises.
    statuses = collections.Counter(factory.get("status") for factory in report_factories)
    for key, status in (("operational_plan", "OPERATIONAL-PLAN"), ("no_structure", "NO-STRUCTURE")):
        if summary.get(key) != statuses.get(status, 0):
            problems.append(f"the summary says {summary.get(key)} of {key} while the records it lists "
                            f"hold {statuses.get(status, 0)}")
    carried_silent = sorted(factory.get("id") for factory in report_factories
                            if factory.get("status") != "OPERATIONAL-PLAN"
                            and factory.get("content_redistributed"))
    strays = [identifier for identifier in carried_silent if identifier not in REFERENCE_RECORDS]
    if strays:
        problems.append("these records carry content and define no stages, workers, gates or prompts: "
                        + ", ".join(strays))
    if summary.get("carried_without_definitions") != len(carried_silent):
        problems.append(f"the summary counts {summary.get('carried_without_definitions')} carried "
                        f"records without definitions while the records list {len(carried_silent)}")
    unused = [identifier for identifier in REFERENCE_RECORDS if identifier not in report_ids]
    if unused:
        warnings.append("the allowance for a carried README is unused by: " + ", ".join(unused)
                        + "; the record was removed or now defines something, and the allowance in "
                          "factory_growth_judge.py should be pruned")

    # 6. The licence wall: content travels only under a licence the sealer itself accepts.
    offenders = sorted(f'{factory.get("id")} ({factory.get("license")})'
                       for factory in seal_factories_list
                       if factory.get("content_redistributed")
                       and factory.get("license") not in seal_factories.PERMISSIVE_LICENCES)
    if offenders:
        problems.append(f"{len(offenders)} record(s) carry content under a licence the seal does not "
                        f"accept: " + ", ".join(offenders[:8])
                        + (f", and {len(offenders) - 8} more" if len(offenders) > 8 else ""))

    # 7. Definitions falling while the counts hold is not fatal - an upstream slice can change shape -
    #    but it is a change in what the set can do, so it is said out loud rather than passing silently.
    for key in ("stages_defined", "workers_defined"):
        if summary.get(key) is None:
            warnings.append(f"the run report carries no {key} count")
        elif summary[key] < LEDGER[key]:
            warnings.append(f"the set defines {summary[key]} {key.split('_')[0]}, fewer than the "
                            f"{LEDGER[key]} the committed set defines")
    licences = contents.get("with_license_text")
    if licences is None:
        warnings.append("the seal does not say how many licence texts it holds")
    elif licences < LEDGER["licence_texts"]:
        warnings.append(f"the seal holds {licences} licence texts, fewer than the "
                        f"{LEDGER['licence_texts']} the committed set holds: a repository may have "
                        f"removed or relicensed the text this record was fetched under")

    if records is not None and operational is not None:
        evidence.append(f'factory set: {records} records ({operational} operational, '
                        f'{summary.get("no_structure")} without definitions), '
                        f'{contents.get("content_redistributed")} carrying content under '
                        f'{contents.get("with_license_text")} licence texts')
        evidence.append(f'ledger floor: {LEDGER["records"]} records, {LEDGER["operational_plan"]} '
                        f'operational, {LEDGER["imported_records"]} imported (measured from the '
                        f'committed seal 3b7d58ab…)')
        grown = (records - LEDGER["records"], operational - LEDGER["operational_plan"],
                 len(registry) - LEDGER["imported_records"])
        if any(value > 0 for value in grown):
            evidence.append(f'grown since the ledger: +{grown[0]} records, +{grown[1]} operational, '
                            f'+{grown[2]} imported')
        evidence.append("the two reference records that carry a README and define nothing: "
                        + ", ".join(identifier for identifier in REFERENCE_RECORDS
                                    if identifier in report_ids))
    return problems, warnings, evidence


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print("usage: factory_growth_judge.py <RUN-REPORT.json> <SEAL.json> <imported_factories.json>",
              file=sys.stderr)
        return 2
    problems, warnings, evidence = judge(Path(argv[1]), Path(argv[2]), Path(argv[3]))
    for line in evidence:
        print(line)
    for warning in warnings:
        print(f"factory growth warning: {warning}")
        print(f"::warning title=Factory growth::{warning}")
    for problem in problems:
        print(f"FACTORY GROWTH FAIL: {problem}", file=sys.stderr)
        print(f"::error title=Factory growth judge::{problem}")
    if problems:
        return 1
    if evidence:
        print(f"::notice title=Factory set::{evidence[0]}")
    print("factory growth judge: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
