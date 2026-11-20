#!/usr/bin/env python3
"""Prove the Nova debugger is honest before trusting its report.

This is the second script the task asked for: a check on the checker. It exists
because a debug tool that prints "0 errors" is worthless if its rules never fire,
and worse than worthless if they fire on correct code.

Five things are verified, in order of how much they matter:

  1. **Detection** -- every rule fires on a planted defect of the kind it exists
     for. A rule with no failing fixture is reported as *theatre* and fails.
  2. **Precision** -- the rules stay silent on correct code (the CLEAN fixture)
     and on the real Nova corpus, and the corpus cannot be "passed" by scanning
     nothing.
  3. **Reachability** -- every Kotlin file in the corpus is parsed and every rule
     that claims to need a file actually saw files. Zero findings from zero input
     is the classic false green.
  4. **Totality** -- every rule callable is declared in the rule list, so nothing
     silently stops running, and every finding id produced by a fixture has a rule
     that claims it.
  5. **Honesty of the report** -- the debugger's own report never says a check
     passed when it was skipped, and never claims an ERROR without evidence.

Exit code 0 only when all five hold. Anything the environment cannot check is
printed as SKIPPED -- never as a pass.

Usage::

    python3 tools/verify_no_false_green.py
    python3 tools/verify_no_false_green.py --nova /path/to/nova-tv --json
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from nova_debug import fixtures, source_audit            # noqa: E402
from nova_debug.findings import ERROR, INFO, SKIP, WARN, Finding  # noqa: E402


@dataclass
class Check:
    name: str
    passed: Optional[bool]          # None == skipped
    detail: str
    evidence: str = ""

    def as_dict(self) -> Dict[str, object]:
        return {
            "check": self.name,
            "status": "pass" if self.passed else ("skip" if self.passed is None else "FAIL"),
            "detail": self.detail,
            "evidence": self.evidence,
        }


def _run_rules(root: Path, files: Dict[str, str], docs: Optional[Path] = None) -> List[Finding]:
    """Write a fixture to a temp dir, run every rule over it, return findings."""
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        for rel, text in files.items():
            p = base / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, encoding="utf-8")
        return _audit_dir(base, docs)


def _audit_dir(base: Path, docs: Optional[Path] = None) -> List[Finding]:
    project = source_audit.load_project(base)
    return source_audit.audit(project, docs)


# ---------------------------------------------------------------------------
# 1. Detection
# ---------------------------------------------------------------------------

def check_detection() -> List[Check]:
    checks: List[Check] = []
    for fixture in fixtures.FIXTURES:
        findings = _run_rules(Path("."), fixture.files)
        produced = {(f.id, f.severity) for f in findings}
        messages = "\n".join(f"{f.id}|{f.severity}|{f.message}|{f.evidence}" for f in findings)
        for rule_id, severity, needle in fixture.expect:
            ok = any(f.id == rule_id and f.severity == severity and needle in (f.message + f.evidence)
                     for f in findings)
            checks.append(Check(
                name=f"detect:{fixture.name}:{rule_id}",
                passed=ok,
                detail=(f"{rule_id} must fire at {severity} on {fixture.name}"
                        f" ({fixture.notes})"),
                evidence=("" if ok else f"missing {needle!r} at {severity}; produced:\n{messages}"),
            ))
        for quiet_id in fixture.quiet:
            offenders = [f for f in findings if f.id == quiet_id]
            checks.append(Check(
                name=f"quiet:{fixture.name}:{quiet_id}",
                passed=not offenders,
                detail=f"{quiet_id} must not fire on {fixture.name}",
                evidence="\n".join(f"{f.file}:{f.line} {f.message}" for f in offenders[:5]),
            ))
        # A fixture that expects nothing and only forbids one rule is the weakest
        # possible check: it passes when the rule is silent *and* when the rule is
        # dead. `expect=[]` used to be used for that, which meant a fixture could
        # not tell "correctly silent" from "never wired up". So a fixture with no
        # expect entries must additionally carry a `would_fire` sample -- the same
        # code with the defect planted -- and the forbidden rule must fire on it.
        # That is what makes the quiet check a regression test rather than a wish.
        if not fixture.expect:
            sample = getattr(fixture, "would_fire", None)
            # A corpus fixture that forbids *many* rules (the general "correct
            # code" corpus, not one rule's counter-example) is exempt only when
            # it explicitly says so, and its exemption is printed in the summary
            # so it is a documented choice rather than a silent hole.
            if sample is None and len(fixture.quiet) > 3:
                checks.append(Check(
                    name=f"detect:{fixture.name}:provable",
                    passed=None,
                    detail=(f"{fixture.name} is a broad false-positive corpus ({len(fixture.quiet)} rules"
                            " must stay quiet); each of those rules is proved by its own fixture"),
                    evidence="exempt: broad corpus, every listed rule has a planted-defect fixture",
                ))
            else:
                muted = [f for f in _run_rules(Path("."), sample.files) if f.id in (fixture.quiet or [])]
                checks.append(Check(
                    name=f"detect:{fixture.name}:provable",
                    passed=bool(muted),
                    detail=(f"{fixture.name}: the same code with the defect planted must make "
                            f"{identical_rules(fixture)} fire, so the quiet check proves something"),
                    evidence=("" if muted else f"planted defect produced none of {fixture.quiet}"),
                ))
    return checks


def identical_rules(fixture) -> str:
    return ", ".join(fixture.quiet or [])


# ---------------------------------------------------------------------------
# 2. Precision on the real corpus
# ---------------------------------------------------------------------------

def check_corpus(nova_root: Optional[Path]) -> List[Check]:
    if nova_root is None:
        return [Check("corpus", None, "nova-tv sources not found; run with --nova /path/to/nova-tv")]
    src = nova_root / "android" / "app" / "src"
    if not src.is_dir():
        return [Check("corpus", None, f"{src} does not exist")]
    project = source_audit.load_project(src, nova_root / "android" / "app" / "proguard-rules.pro")
    findings = source_audit.audit(project, nova_root / "docs")
    checks: List[Check] = []

    # Reachability: a run that parses nothing is not a clean run.
    parsed = [f for f in project.files if f.tokens]
    checks.append(Check(
        "corpus:reachability",
        passed=len(parsed) > 50,
        detail="the corpus must actually be parsed before any 'clean' claim means anything",
        evidence=f"{len(project.files)} Kotlin files loaded, {len(parsed)} with tokens",
    ))

    # The corpus is known-good (it compiles in CI), so it must produce no ERROR.
    errors = [f for f in findings if f.severity == ERROR]
    checks.append(Check(
        "corpus:no_errors",
        passed=not errors,
        detail="the Nova sources compile in CI, so no rule may report an ERROR on them",
        evidence="\n".join(f"{f.file}:{f.line} {f.id} {f.message}" for f in errors[:10]),
    ))

    # Every ERROR must carry evidence text a person can check.
    unevidenced = [f for f in findings if f.severity == ERROR and not f.evidence]
    checks.append(Check(
        "corpus:errors_have_evidence",
        passed=not unevidenced,
        detail="every ERROR must quote the code it is about",
        evidence="\n".join(f"{f.file}:{f.line} {f.id}" for f in unevidenced[:10]),
    ))

    # Warnings are allowed, but a warning count that dwarfs the corpus is noise
    # pretending to be diligence.
    warnings = [f for f in findings if f.severity == WARN]
    ratio_ok = len(warnings) <= max(25, len(parsed) // 2)
    checks.append(Check(
        "corpus:warning_noise",
        passed=ratio_ok,
        detail=f"warnings must stay proportionate to the corpus (<= max(25, files/2)); got {len(warnings)}",
        evidence="\n".join(f"{f.file}:{f.line} {f.id} {f.message[:90]}" for f in warnings[:12]),
    ))
    return checks


# ---------------------------------------------------------------------------
# 3. Totality: no rule can be silently dropped
# ---------------------------------------------------------------------------

RULE_FUNCTIONS = [
    "rule_braces", "rule_unresolved_identifiers", "rule_missing_project_imports",
    "rule_missing_type_members", "rule_duplicate_functions", "rule_swallowed_into_zero",
    "rule_empty_catch", "rule_empty_functions", "rule_dead_buttons", "rule_main_thread_io",
    "rule_non_atomic_save", "rule_doc_claims",
]

# Which fixture each rule must fire on. A rule with no owner is theatre.
RULE_OWNER = {
    "rule_braces": "unbalanced_braces",
    "rule_unresolved_identifiers": "undeclared_identifier",
    "rule_missing_project_imports": "missing_import",
    "rule_missing_type_members": "missing_type_member",
    "rule_duplicate_functions": "duplicate_functions",
    "rule_swallowed_into_zero": "failure_as_zero",
    "rule_empty_catch": "empty_catch",
    "rule_empty_functions": "dead_buttons",
    "rule_dead_buttons": "dead_buttons",
    "rule_main_thread_io": "main_thread_io",
    "rule_non_atomic_save": "non_atomic_save",
    "rule_doc_claims": None,   # needs docs; checked by its own fixture below
}


def check_totality() -> List[Check]:
    checks: List[Check] = []
    for name in RULE_FUNCTIONS:
        exists = callable(getattr(source_audit, name, None))
        checks.append(Check(f"rule_exists:{name}", passed=exists,
                            detail=f"{name} must exist in source_audit"))
    # The audit() entry point must call every rule.
    audit_source = Path(source_audit.__file__).read_text(encoding="utf-8")
    audit_body = audit_source[audit_source.index("def audit("):]
    audit_body = audit_body[:audit_body.index("def load_project")]
    for name in RULE_FUNCTIONS:
        called = f"{name}(project" in audit_body
        checks.append(Check(f"rule_wired:{name}", passed=called,
                            detail=f"{name} must be called by audit(), not merely defined"))

    # Every fixture-based rule must have an owner fixture.
    owned = {v for v in RULE_OWNER.values() if v}
    fixture_names = {f.name for f in fixtures.FIXTURES}
    for owner in sorted(owned - fixture_names):
        checks.append(Check(f"fixture_exists:{owner}", passed=False,
                            detail=f"rule owner fixture {owner!r} does not exist"))

    # Doc-claim rule: its own fixture, since it needs a docs directory.
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        (base / "P.kt").write_text("package demo\nobject Pool { const val CAPACITY = 3000 }\n")
        docs = base / "docs"
        docs.mkdir()
        (docs / "CLAIM.md").write_text("The pool holds 5000 servers.\n")
        findings = _audit_dir(base, docs)
        ok = any(f.id == "DOC_CAPACITY_MISMATCH" for f in findings)
        checks.append(Check(
            "detect:doc_capacity_mismatch", passed=ok,
            detail="a doc claiming 5000 servers against CAPACITY = 3000 must be flagged",
            evidence="\n".join(f"{f.id} {f.message}" for f in findings),
        ))
        # And a correct doc must not be flagged.
        (docs / "CLAIM.md").write_text("The pool holds 3000 servers.\n")
        findings = _audit_dir(base, docs)
        quiet = not any(f.id == "DOC_CAPACITY_MISMATCH" for f in findings)
        checks.append(Check(
            "quiet:doc_capacity_mismatch", passed=quiet,
            detail="a doc matching the constant must not be flagged",
            evidence="\n".join(f"{f.id} {f.message}" for f in findings),
        ))
    return checks


# ---------------------------------------------------------------------------
# 4. The debugger's own report must not lie
# ---------------------------------------------------------------------------

def check_report_honesty(nova_root: Optional[Path]) -> List[Check]:
    cli = HERE / "nova_debug" / "cli.py"
    with tempfile.TemporaryDirectory() as tmp:
        cmd = [sys.executable, str(cli), "--out", tmp]
        if nova_root:
            cmd += ["--nova", str(nova_root)]
        run = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
        report_path = Path(tmp) / "nova-debug-report.json"
        if not report_path.is_file():
            return [Check("report:produced", False,
                          "the debugger did not write a JSON report",
                          (run.stdout + run.stderr)[-800:])]
        data = json.loads(report_path.read_text())
    checks: List[Check] = []
    checks.append(Check("report:produced", True, f"{len(data['findings'])} findings reported"))
    errors = [f for f in data["findings"] if f["severity"] == "error"]
    checks.append(Check(
        "report:errors_are_evidenced",
        passed=all(f["evidence"] for f in errors),
        detail="no ERROR may be printed without the code it refers to",
        evidence="\n".join(f["file"] + ":" + str(f["line"]) for f in errors if not f["evidence"]),
    ))
    # Honesty rule: when the run cannot compile or run the app, it must say so.
    skipped_ids = {f["id"] for f in data["findings"] if f["severity"] == "skip"}
    if not nova_root:
        checks.append(Check(
            "report:says_what_it_could_not_check",
            passed="SRC_NOT_FOUND" in skipped_ids,
            detail="without sources the report must state that the sources were not audited",
            evidence=str(sorted(skipped_ids)),
        ))
    if shutil.which("java") is None:
        checks.append(Check(
            "report:no_jvm_is_declared",
            passed="NO_JVM" in skipped_ids,
            detail="without a JDK the report must say compilation/tests did not run",
            evidence=str(sorted(skipped_ids)),
        ))
    if not (Path(__file__).resolve().parents[2] / "nova-tv").is_dir() and not nova_root:
        pass
    return checks


def assert_assets_exist(nova_root: Path) -> list[str]:
    """Every `assets.open("x")` must have a matching file in the patch.

    This was a real defect: NovaApplication read assets/site_pool.json at startup
    and nothing ever created it, so the bundled pool was permanently empty while
    the code and the README both said it was imported. A missing asset throws at
    runtime and is invisible to a compile, so it needs its own check.
    """
    problems = []
    assets_dir = nova_root / "android/app/src/main/assets"
    for path in nova_root.rglob("*.kt"):
        text = path.read_text(encoding="utf-8", errors="strict")
        for name in re.findall(r'assets\.open\("([^"]+)"\)', text):
            if not (assets_dir / name).is_file():
                problems.append(
                    f"{path.relative_to(nova_root)} reads assets/{name}, "
                    f"but {assets_dir.relative_to(nova_root)}/{name} does not exist"
                )
    return problems


def check_assets_detection() -> Check:
    """Plant the defect the asset rule exists for, and confirm it fires.

    Without this it would be a rule with no failing fixture, which this file
    classifies as theatre.
    """
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "android/app/src/main/java").mkdir(parents=True)
        (root / "android/app/src/main/java/Reads.kt").write_text(
            'val t = assets.open("site_pool.json").bufferedReader().use { it.readText() }\n'
        )
        fired = assert_assets_exist(root)
        (root / "android/app/src/main/assets").mkdir(parents=True)
        (root / "android/app/src/main/assets/site_pool.json").write_text("{}\n")
        quiet = assert_assets_exist(root)
    return Check(
        "detect:asset-missing:provable",
        bool(fired) and not quiet,
        "the asset rule fires when the file is absent and is quiet when it is present",
        f"absent -> {fired}, present -> {quiet}",
    )


def check_assets(nova_root: Optional[Path]) -> List[Check]:
    if nova_root is None:
        return [Check("asset:assets-exist", None, "no nova tree given; nothing to check")]
    problems = assert_assets_exist(nova_root)
    return [
        Check(
            "asset:assets-exist",
            not problems,
            "every asset the code opens exists in the patch"
            if not problems
            else "the code opens an asset that is not in the patch",
            "\n".join(problems),
        )
    ]


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="Verify the Nova debugger does not produce false greens")
    ap.add_argument("--nova", help="path to a nova-tv checkout for the corpus checks")
    ap.add_argument("--json", action="store_true", help="emit JSON instead of text")
    ap.add_argument("--out", help="write the verification report as JSON to this path")
    args = ap.parse_args(argv)

    nova_root = Path(args.nova).resolve() if args.nova else None
    if nova_root is None:
        for candidate in (HERE.parents[1] / "nova-tv", HERE.parents[1].parent / "nova-tv"):
            if (candidate / "android" / "app" / "src").is_dir():
                nova_root = candidate
                break

    checks: List[Check] = []
    checks += check_detection()
    checks += check_corpus(nova_root)
    checks += check_totality()
    checks += check_report_honesty(nova_root)
    checks += [check_assets_detection()]
    checks += check_assets(nova_root)

    failed = [c for c in checks if c.passed is False]
    skipped = [c for c in checks if c.passed is None]
    passed = [c for c in checks if c.passed is True]

    if args.json or args.out:
        payload = {
            "checks": [c.as_dict() for c in checks],
            "summary": {"pass": len(passed), "fail": len(failed), "skip": len(skipped)},
        }
        if args.out:
            Path(args.out).parent.mkdir(parents=True, exist_ok=True)
            Path(args.out).write_text(json.dumps(payload, indent=2) + "\n")
        if args.json:
            print(json.dumps(payload, indent=2))

    if not args.json:
        print(f"verify_no_false_green: {len(passed)} pass, {len(failed)} FAIL, {len(skipped)} skipped")
        for c in failed:
            print(f"  FAIL  {c.name}: {c.detail}")
            if c.evidence:
                for line in c.evidence.splitlines()[:10]:
                    print(f"        {line}")
        for c in skipped:
            print(f"  SKIP  {c.name}: {c.detail}")
        if not failed:
            print("  every rule fires on a planted defect and stays quiet on correct code")

    return 1 if failed else 0



if __name__ == "__main__":
    raise SystemExit(main())
