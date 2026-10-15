"""Tests for tools/ci/published_environment_judge.py, the gate on the published copy's own run.

A gate is only worth having if it refuses. These tests hand the judge synthetic runs built the way the
sealed environment writes them, and assert that it accepts exactly the run that proves the copy ran
itself - every role executed, every check passed, no step failed, no model call made - while naming each
way a run can fail to prove it: a report that is missing, a status that is not RUN, a role that got no
instance, counts below the roles, a failed check, a failed step, a model call on a run asked for none,
and an environment or tool set that is not there at all.
"""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
JUDGE_PATH = REPO_ROOT / "tools" / "ci" / "published_environment_judge.py"


def _load():
    spec = importlib.util.spec_from_file_location("published_environment_judge_under_test", JUDGE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


JUDGE = _load()


class JudgeTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def write_report(self, *, roles: int = 201, executed: int | None = None,
                     counts: dict | None = None, status: str = "RUN", model_calls: int = 0,
                     environment: dict | None = None, factories: int = 171,
                     unexecuted: list | None = None) -> Path:
        """Writes a report shaped the way the sealed environment writes one."""
        swarm_counts = {"model": 0, "offline": roles, "model_failed": 0, "steps_ok": 205,
                        "steps_failed": 0, "checks_passed": roles, "checks_failed": 0,
                        "artefacts": roles}
        if counts:
            swarm_counts.update(counts)
        environment = environment if environment is not None else {
            "tools": {"native": 48, "mcp_bound": 0, "total": 48},
            "agents": {"roles": roles},
            "swarm": {"plan": {"instances": roles, "executed": roles if executed is None else executed,
                               "roles_not_executed": unexecuted or []},
                      "counts": swarm_counts},
        }
        report = {"status": status, "model_calls": model_calls, "environment": environment,
                  "factories": [{"id": f"factory-{index}"} for index in range(factories)]}
        path = self.root / "RUN-REPORT.json"
        path.write_text(json.dumps(report), encoding="utf-8")
        return path

    def test_a_copy_that_ran_every_role_is_accepted(self) -> None:
        problems, evidence = JUDGE.judge(self.write_report())
        self.assertEqual([], problems)
        self.assertIn("201 agent roles", evidence[0])
        self.assertIn("201 agent instances executed", evidence[0])
        self.assertIn("201/201 checks passed", evidence[0])

    def test_a_report_that_is_not_json_is_refused(self) -> None:
        path = self.root / "RUN-REPORT.json"
        path.write_text("not json at all", encoding="utf-8")
        problems, _ = JUDGE.judge(path)
        self.assertTrue(any("could not be read as JSON" in problem for problem in problems), problems)

    def test_a_missing_report_is_refused(self) -> None:
        problems, _ = JUDGE.judge(self.root / "nope.json")
        self.assertTrue(any("could not be read as JSON" in problem for problem in problems), problems)

    def test_a_run_that_did_not_finish_is_refused(self) -> None:
        problems, _ = JUDGE.judge(self.write_report(status="PLAN"))
        self.assertTrue(any("not RUN" in problem for problem in problems), problems)

    def test_a_role_that_got_no_instance_is_refused(self) -> None:
        """The whole claim is that every role the copy carries can run; a cap that skipped some says
        nothing about those."""
        problems, _ = JUDGE.judge(self.write_report(executed=200, unexecuted=["worker-200"]))
        self.assertTrue(any("part of the copy never ran" in problem for problem in problems), problems)
        self.assertTrue(any("worker-200" in problem for problem in problems), problems)

    def test_a_failed_check_inside_the_copy_is_refused(self) -> None:
        problems, _ = JUDGE.judge(self.write_report(counts={"checks_passed": 200, "checks_failed": 1}))
        self.assertTrue(any("1 check(s) failed inside the published copy" in problem
                            for problem in problems), problems)

    def test_a_failed_step_inside_the_copy_is_refused(self) -> None:
        problems, _ = JUDGE.judge(self.write_report(counts={"steps_ok": 204, "steps_failed": 1}))
        self.assertTrue(any("1 agent tool step(s) failed inside the published copy" in problem
                            for problem in problems), problems)

    def test_a_model_call_on_a_run_asked_for_none_is_refused(self) -> None:
        problems, _ = JUDGE.judge(self.write_report(model_calls=2))
        self.assertTrue(any("model call(s) on a run asked for none" in problem for problem in problems),
                        problems)

    def test_a_run_with_no_environment_is_refused(self) -> None:
        problems, _ = JUDGE.judge(self.write_report(environment={}))
        self.assertTrue(any("did not run itself" in problem for problem in problems), problems)

    def test_a_run_with_no_factories_is_refused(self) -> None:
        problems, _ = JUDGE.judge(self.write_report(factories=0))
        self.assertTrue(any("it ran nothing" in problem for problem in problems), problems)

    def test_a_run_with_no_tools_is_refused(self) -> None:
        problems, _ = JUDGE.judge(self.write_report(environment={
            "tools": {"native": 0, "mcp_bound": 0, "total": 0},
            "agents": {"roles": 201},
            "swarm": {"plan": {"executed": 201, "roles_not_executed": []},
                      "counts": {"checks_passed": 201, "checks_failed": 0, "steps_failed": 0}}}))
        self.assertTrue(any("no tools" in problem for problem in problems), problems)

    def test_the_judge_never_passes_a_run_it_could_not_read(self) -> None:
        """Every refusal above is a problem; this pins that none of them comes back as evidence only."""
        for path, label in ((self.root / "missing.json", "missing"),
                            (self.write_report(status="PLAN"), "unfinished"),
                            (self.write_report(model_calls=1), "modelled")):
            with self.subTest(report=label):
                problems, evidence = JUDGE.judge(path)
                self.assertTrue(problems, f"{label} was accepted: {evidence}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
