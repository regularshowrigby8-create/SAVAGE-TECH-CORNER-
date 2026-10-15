"""Tests for tools/ci/airgap_judge.py, the gate on the air-gapped sealed run.

A gate is only worth having if it refuses. These tests hand the judge synthetic runs built the way the
sealed environment writes them - a report with swarm counts, and a researcher artefact whose
`github.probe` step records why it could not reach GitHub - and assert that it accepts exactly the run
that proves the switch was on, while naming each way a run can fail to prove it: a probe that reached
the network, a probe that is missing, a probe that failed, failed checks, a failed step, and a model
call that was made or claimed on a run asked for none.
"""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
JUDGE_PATH = REPO_ROOT / "tools" / "ci" / "airgap_judge.py"


def _load():
    spec = importlib.util.spec_from_file_location("airgap_judge_under_test", JUDGE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


JUDGE = _load()

SWITCH_SUMMARY = "available=False; status=0; reason=network calls are disabled: SEALED_NO_NETWORK is set"
REACHED_SUMMARY = "available=True; status=200; reason=the API answered /rate_limit"


class JudgeTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def write_run(self, *, counts: dict | None = None, model_calls: int = 0,
                  probe: dict | None = None, artefact: bool = True,
                  role: str = "researcher") -> Path:
        """Writes a run shaped the way the sealed environment writes one, and returns the report."""
        report = {"status": "RUN", "model_calls": model_calls,
                  "environment": {"swarm": {"counts": counts if counts is not None else {
                      "model": 0, "offline": 159, "model_failed": 0, "steps_ok": 163, "steps_failed": 0,
                      "checks_passed": 159, "checks_failed": 0, "artefacts": 159}}}}
        report_path = self.root / "RUN-REPORT.json"
        report_path.write_text(json.dumps(report), encoding="utf-8")
        if artefact:
            where = self.root / ".sealed" / "swarm" / "20261014T000000Z" / "artefacts"
            where.mkdir(parents=True, exist_ok=True)
            steps = [] if probe is None else [probe]
            (where / f"{role}-0000000.json").write_text(
                json.dumps({"role": role, "status": "OFFLINE", "steps": steps}), encoding="utf-8")
        return report_path

    def probe_step(self, summary: str, ok: bool = True, error: str = "") -> dict:
        step = {"tool": "github.probe", "ok": ok, "summary": summary}
        if error:
            step["error"] = error
        return step

    def test_the_air_gapped_run_is_accepted_and_its_evidence_printed(self) -> None:
        report = self.write_run(probe=self.probe_step(SWITCH_SUMMARY))
        problems, evidence = JUDGE.judge(report, self.root)
        self.assertEqual([], problems)
        joined = "\n".join(evidence)
        self.assertIn("github.probe -> " + SWITCH_SUMMARY, joined)
        self.assertIn("159/159 checks", joined)
        self.assertIn("0 model calls", joined)

    def test_a_probe_that_reached_the_network_is_rejected(self) -> None:
        """The failure this gate exists for: the run passed, but the air gap was not the path taken."""
        report = self.write_run(probe=self.probe_step(REACHED_SUMMARY))
        problems, _ = JUDGE.judge(report, self.root)
        self.assertEqual(1, len(problems), problems)
        self.assertIn("did not record the air-gap switch", problems[0])
        self.assertIn("available=True", problems[0])

    def test_a_run_with_no_probe_at_all_is_rejected(self) -> None:
        report = self.write_run(probe=None)
        problems, _ = JUDGE.judge(report, self.root)
        self.assertTrue(any("does not record a github.probe step" in problem for problem in problems),
                        problems)

    def test_a_run_with_no_researcher_artefact_is_rejected(self) -> None:
        report = self.write_run(artefact=False)
        problems, _ = JUDGE.judge(report, self.root)
        self.assertTrue(any("no researcher artefact was written" in problem for problem in problems),
                        problems)

    def test_a_failed_probe_is_rejected_with_its_own_error(self) -> None:
        report = self.write_run(probe=self.probe_step("", ok=False, error="the tool crashed"))
        problems, _ = JUDGE.judge(report, self.root)
        self.assertTrue(any("the tool crashed" in problem for problem in problems), problems)

    def test_failed_checks_steps_and_model_calls_are_each_rejected(self) -> None:
        cases = (
            ({"checks_passed": 158, "checks_failed": 1, "steps_ok": 163, "steps_failed": 0,
              "model": 0, "offline": 159}, "1 check(s) failed"),
            ({"checks_passed": 159, "checks_failed": 0, "steps_ok": 162, "steps_failed": 1,
              "model": 0, "offline": 159}, "1 tool step(s) failed"),
            ({"checks_passed": 159, "checks_failed": 0, "steps_ok": 163, "steps_failed": 0,
              "model": 1, "offline": 158}, "a model call was made or claimed"),
            ({"checks_passed": 0, "checks_failed": 0, "steps_ok": 163, "steps_failed": 0,
              "model": 0, "offline": 159}, "no check passed"),
        )
        for counts, expected in cases:
            with self.subTest(counts=counts):
                report = self.write_run(counts=counts, probe=self.probe_step(SWITCH_SUMMARY))
                problems, _ = JUDGE.judge(report, self.root)
                self.assertTrue(any(expected in problem for problem in problems),
                                f"expected {expected!r} among {problems}")

    def test_a_report_that_is_not_json_is_rejected_rather_than_crashing(self) -> None:
        report = self.root / "RUN-REPORT.json"
        report.write_text("{ this is not json", encoding="utf-8")
        problems, _ = JUDGE.judge(report, self.root)
        self.assertEqual(1, len(problems))
        self.assertIn("could not be read as JSON", problems[0])

    def test_a_report_without_swarm_counts_is_rejected(self) -> None:
        report = self.root / "RUN-REPORT.json"
        report.write_text(json.dumps({"status": "RUN"}), encoding="utf-8")
        problems, _ = JUDGE.judge(report, self.root)
        self.assertTrue(any("carries no swarm counts" in problem for problem in problems), problems)

    def test_the_judge_is_wired_into_the_script_that_runs_it(self) -> None:
        """A judge nothing calls is dead weight; the batch script must call it and honour its status."""
        script = (REPO_ROOT / "tools" / "ci" / "airgap_run.sh").read_text(encoding="utf-8")
        self.assertIn("tools/ci/airgap_judge.py", script)
        self.assertIn("SEALED_NO_NETWORK=1", script)
        self.assertIn("JUDGE_STATUS=$?", script)
        self.assertIn('if [ "$JUDGE_STATUS" -ne 0 ]', script)
        # The artefact the judge reads must come from this run, not from whatever ran before it.
        self.assertIn('rm -rf "$SWARM_ROOT/.sealed/swarm"', script)
        for forbidden in ("|| true", "continue-on-error"):
            self.assertNotIn(forbidden, script)

    def test_ci_runs_the_air_gapped_job_and_installs_nothing_into_it(self) -> None:
        """The offline claim is proven by a job, and that job must not need a registry to work.

        The job runs the script, sets up nothing but Python, and never installs npm dependencies: the
        sealed runtime is standard-library only, so a job that installs nothing is a job that can prove
        a machine with no registry can run the component. A job that grows an `npm ci` would quietly
        stop proving that, which is what this pins.
        """
        workflow = (REPO_ROOT / ".github" / "workflows" / "quality-conveyor-belt.yml").read_text(
            encoding="utf-8")
        self.assertIn("airgapped_sealed_run:", workflow)
        self.assertIn("Air-gapped sealed run (SEALED_NO_NETWORK=1)", workflow)
        job = workflow.split("airgapped_sealed_run:")[1]
        self.assertIn("bash tools/ci/airgap_run.sh", job)
        self.assertNotIn("npm ci", job)
        self.assertNotIn("npm install", job)
        self.assertNotIn("pip install", job)
        for forbidden in ("continue-on-error", "|| true"):
            self.assertNotIn(forbidden, job)


if __name__ == "__main__":
    unittest.main(verbosity=2)
