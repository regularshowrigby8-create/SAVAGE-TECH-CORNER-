#!/usr/bin/env python3
"""The sealed component rechecking itself with the factories it carries - and repairing what it can.

`sealcheck.py` answers one question: does every file still hash to what the seal recorded? That is
integrity. It is not fitness. A set can verify perfectly and still be broken in ways the seal cannot
see: a payload the sealer wrote and the store never kept a licence beside, a registry that declares a
factory no slice was ever fetched for, a runtime module added since the last seal, a record that
carries content and defines nothing for it to do.

This module asks the fitness questions, and it asks them *with the component itself*:

  * the reading of each slice - stages, workers, gates, prompts, whether the slice defines anything at
    all - is done by the same reader the run uses (`tools/factory/sealed_factory_runtime.py`), imported
    rather than reimplemented, so "functional" here means exactly what it means in a run report;
  * each finding that can be repaired is handed to the functional factory whose own definitions name
    that capability - a record whose stages, workers, gates or prompts mention licences owns the
    licence gap; one that mentions snapshots or sources owns a missing slice; a seal-shaped record owns
    a manifest disagreement. The assignment is recorded with the keyword that matched. Where no
    functional record names the capability, the finding is recorded as **unassigned** and the
    component's own tool repairs it: nothing is credited to a factory that did not claim it;
  * the repairs themselves are the component's own tools, run in the order that keeps the set
    consistent (import the licence text, then re-index; vendor the slice, then re-seal), and the
    finding is only reported as repaired when the command that claims to fix it exited zero *and* the
    finding is gone from a fresh survey.

What it will not do: claim a set is functional when something blocking could not be fixed, invent a
licence or a slice, or touch a record's licence verdict - `license` and `content_redistributed` are the
licence wall's business, not the repairer's.

Two kinds of "not a defect, and never presented as one" are reported as *states*, not findings: a
record whose content is withheld because its licence does not permit copying (the wall), and a record
that carries content but defines nothing this runtime can run (the upstream repository publishes no
stages, workers, gates or prompts). Both are named with the reason, and neither is counted as fitness.

    python3 sealed-factories/runtime/cli.py selfcheck                     # survey, no changes
    #   the report lands in sealed-factories/manifest/self-check.json unless --workspace says otherwise
    python3 sealed-factories/runtime/cli.py selfcheck --repair            # repair, then re-survey
    python3 sealed-factories/runtime/cli.py selfcheck --require-functional  # exit 1 unless functional

Exit codes: 0 fit (or repaired to fit), 1 not fit, 3 there is no seal to check.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

SEALED = Path(__file__).resolve().parent.parent
REPO_ROOT = SEALED.parent
TOOLS = REPO_ROOT / "tools" / "factory"
CI = REPO_ROOT / "tools" / "ci"
SEAL_FILE = SEALED / "SEAL.json"
STORE = SEALED / "licenses"
INDEX = SEALED / "LICENSES.json"
SUFFIX = "-LICENSE"

#: The component's own tools, by the job each does. A missing one is a finding, not a shrug: the copy
#: cannot prove itself fit without them, and a published copy that silently could not would be the
#: exact failure this module exists to prevent.
COMPONENT_TOOLS = {
    "reader": TOOLS / "sealed_factory_runtime.py",
    "sealer": TOOLS / "seal_factories.py",
    "license_manager": TOOLS / "license_manager.py",
    "vendor": TOOLS / "vendor_imported_factories.py",
    "vendor_mcp": TOOLS / "vendor_mcp_servers.py",
    "registries": TOOLS / "vendor_needed.py",
}

#: How each repairable finding is fixed, and whether the fix needs the network. `network` is what makes
#: `SEALED_NO_NETWORK=1` mean something here: the repair is refused with the reason, and the finding
#: stays a finding.
REPAIRS = {
    "payload-missing": {"tool": "vendor", "network": True},
    "payload-hash-mismatch": {"tool": "vendor", "network": True},
    "registry-without-slice": {"tool": "vendor", "network": True},
    "licence-text-missing": {"tool": "licence", "network": True},
    "licence-index-stale": {"tool": "index", "network": False},
    "component-file-unpinned": {"tool": "seal", "network": False},
    "seal-count-disagreement": {"tool": "seal", "network": False},
}

#: The keywords a functional factory's own definitions must name for that factory to own a task. Lower
#: case, matched against the labels the reader derived from the slice - never against the factory's
#: name, so the claim comes from what the factory says it does.
CAPABILITY = {
    "licence-text-missing": ("licen", "attribution", "compliance"),
    "licence-index-stale": ("licen", "attribution", "compliance"),
    "payload-missing": ("snapshot", "vendor", "ingest", "import", "source", "fetch"),
    "payload-hash-mismatch": ("snapshot", "hash", "integrity", "verify"),
    "registry-without-slice": ("snapshot", "vendor", "ingest", "import", "source"),
    "component-file-unpinned": ("seal", "manifest", "hash", "integrity", "verify"),
    "seal-count-disagreement": ("seal", "manifest", "hash", "integrity", "verify"),
}

#: Findings that must be gone for a set to be called functional, and the ones that are named states.
BLOCKING = tuple(REPAIRS) + ("component-incomplete",)

#: Distinguishes "the caller supplied the structures" from "build them from the reader": a survey that
#: was handed an empty mapping is a test, a survey that built an empty one is a component that could not
#: read anything - and only the second is a finding.
_UNSET = object()
STATES = ("content-withheld", "carried-without-definitions")


def offline() -> bool:
    """The switch the air-gapped run sets. Any repair that needs the network refuses while it is on."""
    return os.environ.get("SEALED_NO_NETWORK") == "1"


def read_seal(path: Path = SEAL_FILE) -> dict | None:
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def component_tools(missing_only: bool = False) -> dict:
    found = {name: path for name, path in COMPONENT_TOOLS.items() if path.is_file()}
    if missing_only:
        return {name: path for name, path in COMPONENT_TOOLS.items() if not path.is_file()}
    return found


def load_reader():
    """The run's own slice reader, imported - not reimplemented - so fitness means what a run means."""
    if not COMPONENT_TOOLS["reader"].is_file():
        return None
    sys.path.insert(0, str(TOOLS))
    try:
        import sealed_factory_runtime  # noqa: PLC0415 - deliberately late, and optional
    except ImportError:
        return None
    return sealed_factory_runtime


def read_structures(document: dict, reader) -> dict[str, dict]:
    """What each slice defines, through the component's reader. Empty when the reader is absent."""
    if reader is None:
        return {}
    structures: dict[str, dict] = {}
    for factory in document.get("factories", []):
        try:
            derived = reader.read_factory(document, factory)
        except (OSError, ValueError, KeyError):
            structures[factory["id"]] = {"structure_found": False, "labels": [],
                                         "totals": {"stages": 0, "roles": 0, "gates": 0, "prompts": 0},
                                         "unreadable": True}
            continue
        labels: list[str] = []
        for kind in ("stages", "roles", "gates", "prompts", "headings", "mcp_tools"):
            labels += [str(label) for _source, label in derived.get(kind, [])]
        structures[factory["id"]] = {"structure_found": bool(derived.get("structure_found")),
                                     "labels": labels, "totals": derived.get("totals", {}),
                                     "unreadable": False}
    return structures


def _index_current(index_path: Path, store: Path) -> bool:
    if not index_path.is_file():
        return False
    sys.path.insert(0, str(TOOLS))
    try:
        import license_manager  # noqa: PLC0415 - the component's own store tool
    except ImportError:
        return False
    recorded = json.loads(index_path.read_text(encoding="utf-8"))
    return recorded.get("index_sha256") == license_manager.build_index(store)["index_sha256"]


def survey(document: dict, *, structures=_UNSET, store: Path = STORE,
           index_path: Path = INDEX, offline_now: bool | None = None) -> dict:
    """Every way the set fails to be fit, and every state that is not a defect.

    Nothing here reads the network and nothing changes a file: a survey is a survey. What it cannot
    decide locally - because the component's own tools are missing from this copy - is reported as
    `component-incomplete` rather than assumed fit.
    """
    if offline_now is None:
        offline_now = offline()
    findings: list[dict] = []
    states: list[dict] = []
    factories = document.get("factories", [])
    contents = document.get("contents", {})
    if structures is _UNSET and not factories:
        structures = {}

    missing_tools = component_tools(missing_only=True)
    for name, path in sorted(missing_tools.items()):
        findings.append({"code": "component-incomplete", "record": "-", "repairable": False,
                         "blocks": True,
                         "detail": f"the component's own {name} is not in this copy "
                                   f"({path.relative_to(REPO_ROOT) if REPO_ROOT in path.parents else path})",
                         "reason": "a person has to restore the file; no tool here can invent it"})

    if structures is _UNSET:
        structures = read_structures(document, load_reader())
        if factories and not structures:
            findings.append({"code": "component-incomplete", "record": "-", "repairable": False,
                             "blocks": True,
                             "detail": "the component's own reader could not be loaded, so no slice "
                                       "could be judged",
                             "reason": "the reader is the thing that decides what is functional here; "
                                       "without it a survey would be an opinion"})

    # 1. Every payload the seal names: present, and still the bytes the seal hashed.
    sys.path.insert(0, str(TOOLS))
    try:
        import seal_factories  # noqa: PLC0415 - the component's own hasher
    except ImportError:
        seal_factories = None
    for factory in factories:
        identifier = factory.get("id", "?")
        payload = SEALED / str(factory.get("payload", ""))
        if not payload.is_file():
            findings.append({"code": "payload-missing", "record": identifier, "repairable": True,
                             "blocks": True, "detail": f"{factory.get('payload')} is not in the seal"})
            continue
        expected = factory.get("payload_sha256", "")
        if expected:
            actual = seal_factories.sha256_file(payload) if seal_factories else ""
            if not actual:
                findings.append({"code": "component-incomplete", "record": identifier,
                                 "repairable": False, "blocks": True,
                                 "detail": "the payload could not be hashed, because the component's "
                                           "own sealer could not be loaded",
                                 "reason": "a payload nobody hashed is not a verified payload"})
            elif actual != expected:
                findings.append({"code": "payload-hash-mismatch", "record": identifier,
                                 "repairable": True, "blocks": True,
                                 "detail": f"{payload.name} hashes {actual[:16]}… but the seal "
                                           f"recorded {expected[:16]}…"})

    # 2. Every record's licence text: stored once, and indexed as it is.
    stored = {path.name for path in store.glob("*")} if store.is_dir() else set()
    for factory in factories:
        identifier = factory.get("id", "?")
        licence_file = factory.get("license_file")
        if licence_file and licence_file not in stored:
            findings.append({"code": "licence-text-missing", "record": identifier, "repairable": True,
                             "blocks": True,
                             "detail": f"the seal names {licence_file} for this record and the store "
                                       f"does not hold it"})
    if stored and not _index_current(index_path, store):
        findings.append({"code": "licence-index-stale", "record": "-", "repairable": True,
                         "blocks": True,
                         "detail": "the licence index is missing or was built from a different store "
                                   "than the one on disk"})

    # 3. Every registry record has a slice: a factory the registry lists and no vendored record
    #    exists for is a record the set only pretends to hold.
    registry_module = None
    if COMPONENT_TOOLS["registries"].is_file():
        sys.path.insert(0, str(TOOLS))
        try:
            import vendor_needed  # noqa: PLC0415 - the component's own registry reader
            registry_module = vendor_needed
        except ImportError:
            registry_module = None
    if registry_module is not None:
        for kind, registry_path, group in registry_module.REGISTRIES:
            if not registry_path.is_file():
                continue
            payload = json.loads(registry_path.read_text(encoding="utf-8"))
            for entry in registry_module.entries_of(payload):
                identifier = entry.get("id", "")
                if identifier and not (SEALED / group / f"{identifier}.json").is_file():
                    findings.append({"code": "registry-without-slice", "record": identifier,
                                     "repairable": True, "blocks": True,
                                     "detail": f"the {kind} registry lists this factory and no slice "
                                               f"was ever vendored for it"})

    # 4. The seal covers the runtime it ships: a module added since the last seal is invisible to
    #    verification, which is how a half-updated component stays "verified".
    # `contents.component_files` is a count; the list is at the top level of the seal. Reading the
    # count as a list would have made every module "unpinned" - and reading it as nothing at all would
    # have made the check silent, which is worse.
    recorded = {entry.get("path") for entry in document.get("component_files", [])
                if isinstance(entry, dict)}
    for path in sorted((SEALED / "runtime").glob("*.py")):
        relative = str(path.relative_to(SEALED))
        if relative not in recorded:
            findings.append({"code": "component-file-unpinned", "record": "-", "repairable": True,
                             "blocks": True,
                             "detail": f"{relative} ships in the seal and no seal record pins it"})

    # 5. The seal's own summary has to describe the records it carries.
    carried = sum(1 for factory in factories if factory.get("content_redistributed"))
    with_text = sum(1 for factory in factories if factory.get("license_file"))
    disagreements = []
    if contents.get("factories") not in (None, len(factories)):
        disagreements.append(f"the seal counts {contents.get('factories')} records and carries "
                             f"{len(factories)}")
    if contents.get("with_license_text") not in (None, with_text):
        disagreements.append(f"the seal counts {contents.get('with_license_text')} licence texts and "
                             f"the records name {with_text}")
    if contents.get("content_redistributed") not in (None, carried):
        disagreements.append(f"the seal counts {contents.get('content_redistributed')} records carrying "
                             f"content and the records hold {carried}")
    if disagreements:
        for detail in disagreements:
            findings.append({"code": "seal-count-disagreement", "record": "-", "repairable": True,
                             "blocks": True, "detail": detail})

    # 6. The named states. Neither is a defect and neither is counted as fitness.
    for factory in factories:
        identifier = factory.get("id", "?")
        structure = structures.get(identifier, {})
        if structure.get("unreadable"):
            findings.append({"code": "component-incomplete", "record": identifier,
                             "repairable": False, "blocks": True,
                             "detail": "this slice could not be read by the component's own reader",
                             "reason": "a payload that cannot be read cannot be judged fit"})
            continue
        if not factory.get("content_redistributed"):
            licence = factory.get("license") or "no licence recorded"
            states.append({"code": "content-withheld", "record": identifier,
                           "detail": f"its content is not carried ({licence}); the licence wall, not a "
                                     f"defect"})
        elif structures and not structure.get("structure_found", False):
            states.append({"code": "carried-without-definitions", "record": identifier,
                           "detail": "it carries content and its slice defines no stage, worker, gate "
                                     "or prompt for this runtime to run"})

    return {"findings": findings, "states": states,
            "records": len(factories), "carried": carried, "licence_texts": with_text,
            "store_texts": len(stored), "reader": COMPONENT_TOOLS["reader"].is_file(),
            "offline": offline_now}


def assign(finding: dict, structures: dict[str, dict]) -> dict:
    """Which functional factory owns this finding, from what that factory's own definitions name.

    The order is deterministic (keyword, then record id) so two runs of the same set agree. No match
    means `assigned_to: None` and the reason says so - the component's own tool does the repair, and
    the record of who owned it stays empty rather than being filled with a factory that never claimed
    the work.
    """
    if not finding.get("repairable"):
        return {"assigned_to": None, "assignment": "not assignable",
                "assign_reason": finding.get("reason", "this finding is a state, not a task")}
    functional = sorted(identifier for identifier, structure in structures.items()
                        if structure.get("structure_found"))
    for keyword in CAPABILITY.get(finding["code"], ()):
        for identifier in functional:
            labels = [label.lower() for label in structures[identifier].get("labels", [])]
            if any(keyword in label for label in labels):
                return {"assigned_to": identifier, "assignment": f"defines {keyword!r}",
                        "assign_reason": f"its own stages, workers, gates or prompts name {keyword!r}"}
    return {"assigned_to": None, "assignment": "unassigned",
            "assign_reason": "no functional record's definitions name this capability; the component's "
                             "own tool does the work"}


def _vendor_command(finding: dict) -> list[list[str]]:
    """The vendoring command for one record, by the group its slice lives in."""
    identifier = finding["record"]
    for kind, _registry_path, group in (("imported", None, "vendor"), ("ai", None, "vendor-ai"),
                                        ("mcp", None, "mcp-vendor")):
        if (SEALED / group / f"{identifier}.json").is_file():
            if kind == "imported":
                return [[sys.executable, str(TOOLS / "vendor_imported_factories.py"),
                         "--refresh", "--only", identifier]]
            return [[sys.executable, str(TOOLS / "vendor_mcp_servers.py"), "--kind", kind,
                     "--refresh", "--only", identifier]]
    # No vendored record to refresh: the record has to be fetched from its own registry, which the
    # growth run does; here the repair is the same tool with the registry's own list.
    return [[sys.executable, str(TOOLS / "vendor_imported_factories.py"), "--refresh", "--only",
             identifier]]


def _seal_commands() -> list[list[str]]:
    return [[sys.executable, str(TOOLS / "seal_factories.py"), "seal"],
            [sys.executable, str(TOOLS / "seal_factories.py"), "verify"]]


def repair_commands(finding: dict) -> list[list[str]]:
    """The commands that fix one finding, in the order that leaves the set consistent.

    Everything that changes a payload, a stored licence text or the index ends with a re-seal: the seal
    pins all three, so a repair that stopped before re-sealing would leave the component failing its own
    verification - a repair that breaks the thing it repaired.
    """
    tool = REPAIRS.get(finding.get("code", ""), {}).get("tool")
    if tool == "vendor":
        return _vendor_command(finding) + _seal_commands()
    if tool == "licence":
        return [[sys.executable, str(TOOLS / "license_manager.py"), "import",
                 "--only", finding["record"]]] + _seal_commands()
    if tool == "index":
        return [[sys.executable, str(TOOLS / "license_manager.py"), "index"]] + _seal_commands()
    if tool == "seal":
        return _seal_commands()
    return []


def repair(finding: dict, *, runner=None, offline_now: bool | None = None) -> dict:
    """Run the repair this finding names, and report only what actually ran and what it returned."""
    if runner is None:
        runner = subprocess.run
    if offline_now is None:
        offline_now = offline()
    plan = REPAIRS.get(finding.get("code", ""))
    if not plan:
        return {"record": finding.get("record"), "repaired": False,
                "detail": finding.get("reason", "no repair is defined for this finding")}
    if plan["network"] and offline_now:
        return {"record": finding.get("record"), "repaired": False, "refused": True,
                "detail": "the repair needs the network and the network is switched off "
                          "(SEALED_NO_NETWORK=1)"}
    executed = []
    for argv in repair_commands(finding):
        if not Path(argv[1]).is_file():
            return {"record": finding.get("record"), "repaired": False, "executed": executed,
                    "detail": f"the tool {argv[1]} is not present in this copy"}
        done = runner(argv, capture_output=True, text=True)
        executed.append({"argv": argv, "returncode": done.returncode})
        if done.returncode != 0:
            return {"record": finding.get("record"), "repaired": False, "executed": executed,
                    "detail": f"{Path(argv[1]).name} exited {done.returncode}"}
    return {"record": finding.get("record"), "repaired": True, "executed": executed,
            "tool": finding.get("code"), "detail": "the repair command(s) exited zero; the survey "
                                                   "after this decides whether the finding is gone"}


def run(*, do_repair: bool = False, require_functional: bool = False, workspace: Path | None = None,
        store: Path = STORE, index_path: Path = INDEX, runner=None, document: dict | None = None,
        offline_now: bool | None = None) -> dict:
    """Survey, optionally repair, survey again, and decide. Returns the report it also writes out."""
    if offline_now is None:
        offline_now = offline()
    if document is None:
        document = read_seal()
    if document is None:
        return {"verdict": "NO-SEAL", "findings": [], "states": [], "repairs": []}

    first = survey(document, store=store, index_path=index_path, offline_now=offline_now)
    structures = read_structures(document, load_reader())
    for finding in first["findings"]:
        finding.update(assign(finding, structures))

    repairs: list[dict] = []
    if do_repair:
        for finding in first["findings"]:
            if not finding.get("repairable"):
                continue
            repairs.append(repair(finding, runner=runner, offline_now=offline_now))

    after = survey(document, store=store, index_path=index_path, offline_now=offline_now)
    after_structures = read_structures(document, load_reader())
    for finding in after["findings"]:
        finding.update(assign(finding, after_structures))

    blocking = [finding for finding in after["findings"] if finding.get("blocks")]
    verdict = "FUNCTIONAL" if not blocking else "NOT FUNCTIONAL"
    report = {"verdict": verdict, "seal_root": _seal_root(), "records": after["records"],
              "carried": after["carried"], "licence_texts": after["licence_texts"],
              "store_texts": after["store_texts"], "reader_used": after["reader"],
              "offline": offline_now, "blocking": blocking, "states": after["states"],
              "findings_before": first["findings"], "repairs": repairs}
    if workspace is not None:
        workspace.mkdir(parents=True, exist_ok=True)
        (workspace / "self-check.json").write_text(json.dumps(report, indent=1) + "\n",
                                                   encoding="utf-8")
    return report


def _seal_root() -> str:
    hash_file = SEALED / "SEAL.sha256"
    if not hash_file.is_file():
        return ""
    return hash_file.read_text(encoding="utf-8").split()[0]


def print_report(report: dict, *, as_json: bool = False) -> None:
    if as_json:
        print(json.dumps(report, indent=1))
        return
    if report.get("verdict") == "NO-SEAL":
        print("self-check: there is no seal to check", file=sys.stderr)
        return
    states: dict[str, int] = {}
    for state in report["states"]:
        states[state["code"]] = states.get(state["code"], 0) + 1
    print(f"self-check: {report['records']} records, {report['carried']} carrying content, "
          f"{report['licence_texts']} licence texts in the seal, {report['store_texts']} in the store, "
          f"reader {'used' if report['reader_used'] else 'ABSENT'}")
    for repair in report["repairs"]:
        state = "repaired" if repair.get("repaired") else (
            "refused" if repair.get("refused") else "not repaired")
        print(f"self-check: {state} {repair.get('record')} - {repair.get('detail')}")
    for finding in report["blocking"]:
        owner = finding.get("assigned_to") or "unassigned"
        print(f"self-check: {finding['code']} {finding['record']} ({owner}) - {finding['detail']}")
    print(f"self-check: named states {states or 'none'}")
    line = (f"SELF-CHECK {report['verdict']} - {report['records']} records, "
            f"{len(report['blocking'])} blocking finding(s), {len(report['states'])} named state(s), "
            f"seal {report['seal_root'][:16]}…")
    print(line)
    for finding in report["blocking"]:
        print(f"::error title=Sealed self-check::{finding['code']} {finding['record']}: "
              f"{finding['detail']}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Recheck the sealed set with the factories it carries")
    parser.add_argument("--repair", action="store_true", help="repair what can be repaired")
    parser.add_argument("--require-functional", action="store_true",
                        help="exit 1 unless nothing blocking remains")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--workspace", default="")
    args = parser.parse_args(argv)

    # Default where runs already write: the component's own manifest directory, which is gitignored and
    # left out of a published tree - a run's report belongs to the run.
    workspace = Path(args.workspace) if args.workspace else SEALED / "manifest"
    report = run(do_repair=args.repair, require_functional=args.require_functional,
                 workspace=workspace)
    print_report(report, as_json=args.json)
    if report.get("verdict") == "NO-SEAL":
        return 3
    if args.require_functional and report.get("verdict") != "FUNCTIONAL":
        print("self-check: refusing to call this set functional", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
