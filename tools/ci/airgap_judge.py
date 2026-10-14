#!/usr/bin/env python3
"""Judge an air-gapped sealed run on what it produced.

`tools/ci/airgap_run.sh` runs the sealed environment with `SEALED_NO_NETWORK=1`; this module is what
decides whether that run proved anything. It lives in its own file, rather than inside the shell
script, so the tests can hand it synthetic runs - including the ones that must fail - instead of
trusting a gate that has only ever been observed to pass.

Usage: airgap_judge.py <RUN-REPORT.json> <swarm-root>
Prints the evidence it found and exits 0, or prints every reason it rejected the run and exits 1.
"""

from __future__ import annotations

import json
import pathlib
import sys

#: The switch's own name, as the tools report it in their refusals. A run judged here has to carry it
#: in a role's artefact: that is what distinguishes "the air-gapped path ran" from "the run happened
#: to pass while a probe quietly reached the network".
SWITCH_MARKER = "SEALED_NO_NETWORK is set"


def judge(report_path: pathlib.Path, swarm_root: pathlib.Path) -> tuple[list[str], list[str]]:
    """Returns (problems, evidence). An empty problem list means the run is accepted."""
    problems: list[str] = []
    evidence: list[str] = []
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as problem:
        return [f"{report_path} could not be read as JSON: {problem}"], evidence

    counts = ((report.get("environment") or {}).get("swarm") or {}).get("counts") or {}
    if not counts:
        problems.append(f"{report_path} carries no swarm counts, so the run cannot be judged")
    else:
        if counts.get("checks_failed"):
            problems.append(f'{counts["checks_failed"]} check(s) failed')
        if counts.get("steps_failed"):
            problems.append(f'{counts["steps_failed"]} tool step(s) failed')
        if not counts.get("checks_passed"):
            problems.append("no check passed, which is not a run")
        if counts.get("model") or counts.get("model_failed"):
            problems.append("a model call was made or claimed on a run asked for 0 model calls")
    if report.get("model_calls"):
        problems.append(f'report records {report["model_calls"]} model call(s)')

    artefacts = sorted(swarm_root.glob(".sealed/swarm/*/artefacts/researcher-*.json"))
    if not artefacts:
        problems.append(f"no researcher artefact was written under {swarm_root}/.sealed/swarm/*/artefacts/")
    else:
        newest = max(artefacts, key=lambda path: path.stat().st_mtime)
        try:
            steps = json.loads(newest.read_text(encoding="utf-8")).get("steps") or []
        except (OSError, ValueError) as problem:
            problems.append(f"{newest} could not be read as JSON: {problem}")
            steps = []
        probe = next((step for step in steps if step.get("tool") == "github.probe"), None)
        if probe is None:
            problems.append(f"{newest} does not record a github.probe step")
        elif not probe.get("ok"):
            problems.append(f"the github.probe step failed: {probe.get('error')}")
        else:
            summary = probe.get("summary") or ""
            if SWITCH_MARKER not in summary:
                problems.append("the github.probe step did not record the air-gap switch; it answered "
                                f"instead: {summary!r}")
            else:
                evidence.append(f"air-gapped evidence: {newest}")
                evidence.append(f"                     github.probe -> {summary}")

    checks = counts.get("checks_passed")
    if checks:
        evidence.append(f"air-gapped run: {checks}/{checks} checks, {counts.get('steps_ok')} tool steps "
                        f"ok, {counts.get('offline')} agents offline, 0 model calls")
    return problems, evidence


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("usage: airgap_judge.py <RUN-REPORT.json> <swarm-root>", file=sys.stderr)
        return 2
    problems, evidence = judge(pathlib.Path(argv[1]), pathlib.Path(argv[2]))
    for line in evidence:
        print(line)
    for problem in problems:
        print(f"AIR-GAP FAIL: {problem}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
