#!/usr/bin/env python3
"""Judge the run the published copy makes of itself, inside the tree about to be published.

`tools/ci/publish_sealed_factory.sh` runs the sealed environment in the snapshot before it publishes it.
This module is what decides whether that run proved anything. It lives in its own file, rather than
inside the shell script, so the tests can hand it synthetic runs - including every run that must be
refused - instead of trusting a gate that has only ever been observed to pass.

The claim being checked is narrower than the belt's, and that is the point: the copy is not being asked
to compete with anything. It is being asked to *run* - its own tools, its own agent roles and its own
swarm, with no model available and nothing but itself - and to report honestly what happened. A copy
that cannot execute one instance of every role it carries, or that fails a step or a check while doing
it, is not a component that was proved functional: it is a component that was proved to have a seal.

Usage: published_environment_judge.py <RUN-REPORT.json>
Prints the evidence it found and exits 0, or prints every reason it refused the run and exits 1.
"""

from __future__ import annotations

import json
import pathlib
import sys

#: The copy is run in plan mode with no model calls, so a run that claims any - or fails one - is not
#: the run this gate asked for, whatever else it reports.
MAX_MODEL_CALLS = 0


def judge(report_path: pathlib.Path) -> tuple[list[str], list[str]]:
    """Returns (problems, evidence). An empty problem list means the run is accepted."""
    problems: list[str] = []
    evidence: list[str] = []
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as problem:
        return [f"{report_path} could not be read as JSON: {problem}"], evidence

    if report.get("status") != "RUN":
        problems.append(f"the run's status is {report.get('status')!r}, not RUN")
    if report.get("model_calls"):
        problems.append(f'the run records {report["model_calls"]} model call(s) on a run asked for none')
    factories = report.get("factories")
    if not isinstance(factories, list) or not factories:
        problems.append("the run report carries no factories, so it ran nothing")
        return problems, evidence

    environment = report.get("environment") or {}
    if not environment:
        problems.append("the run report carries no environment, so the copy did not run itself")
        return problems, evidence

    tools = environment.get("tools") or {}
    agents = environment.get("agents") or {}
    swarm = environment.get("swarm") or {}
    plan = swarm.get("plan") or {}
    counts = swarm.get("counts") or {}

    if not tools.get("total"):
        problems.append("the environment reports no tools, so the copy has none to run")
    roles = agents.get("roles")
    if not isinstance(roles, int) or roles <= 0:
        problems.append(f"the environment reports {roles!r} agent roles, so there is nothing to run")
        return problems, evidence

    executed = plan.get("executed")
    if not isinstance(executed, int):
        problems.append("the run report carries no executed-instance count")
    elif executed < roles:
        # The default is one instance of every role; a run below that executed part of the component
        # and says nothing about the rest of it.
        problems.append(f"the swarm executed {executed} agent instances for {roles} roles, so part of "
                        f"the copy never ran")
    unexecuted = plan.get("roles_not_executed") or []
    if unexecuted:
        problems.append(f"{len(unexecuted)} role(s) got no agent instance at all: "
                        f"{', '.join(str(role) for role in unexecuted[:8])}")

    if not counts:
        problems.append("the run report carries no swarm counts, so the run cannot be judged")
    else:
        if counts.get("checks_failed"):
            problems.append(f'{counts["checks_failed"]} check(s) failed inside the published copy')
        if counts.get("steps_failed"):
            problems.append(f'{counts["steps_failed"]} agent tool step(s) failed inside the published copy')
        if not counts.get("checks_passed"):
            problems.append("no check passed, which is not a run")
        if counts.get("model") or counts.get("model_failed"):
            problems.append("a model call was made or claimed on a run asked for none")

    if not problems:
        evidence.append(
            f"published copy ran itself: {len(factories)} factories, {tools.get('total')} tools "
            f"({tools.get('native')} native, {tools.get('mcp_bound')} bound from MCP), "
            f"{roles} agent roles, {executed} agent instances executed, "
            f"{counts.get('steps_ok')} tool steps ok, "
            f"{counts.get('checks_passed')}/{counts.get('checks_passed')} checks passed, "
            f"0 model calls")
    return problems, evidence


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    problems, evidence = judge(pathlib.Path(argv[1]))
    if problems:
        print("PUBLISHED ENVIRONMENT FAIL")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    for line in evidence:
        print(f"[PUBLISHED] {line}")
    print("PUBLISHED ENVIRONMENT PASS: the copy that is about to be published ran itself")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
