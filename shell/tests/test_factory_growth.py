"""Tests for the GitHub-native growth pipeline: the judge, the vendoring plan, and the wiring.

Growing the set happens on a runner now, against the live GitHub API, and it *commits* what it found.
That makes the judge the last thing standing between a degraded run and a smaller set: a rate-limited
licence scan, an import whose slice never arrived, a record that carries content under a licence the
seal refuses - none of those may end up committed as if they were growth. These tests hand the judge
synthetic sets built to the shape of the real one, including every set that must be refused, and pin
the two wiring properties that would otherwise go quiet: the script honours the judge's status, and the
workflow verifies before it commits and asks the belt to run on what it pushed.
"""

from __future__ import annotations

import importlib.util
import json
import re
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
JUDGE_PATH = REPO_ROOT / "tools" / "ci" / "factory_growth_judge.py"
VENDOR_NEEDED_PATH = REPO_ROOT / "tools" / "factory" / "vendor_needed.py"
SCRIPT = REPO_ROOT / "tools" / "ci" / "factory_growth.sh"
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "factory-growth.yml"


def _load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


JUDGE = _load(JUDGE_PATH, "factory_growth_judge_under_test")
VENDOR_NEEDED = _load(VENDOR_NEEDED_PATH, "vendor_needed_under_test")

#: The committed set, as SEAL.json and the run that followed it report it (2026-10-15). Every fixture
#: below is this, with exactly one thing changed.
SEAL_COUNT = 149
CARRIED = 113
LICENCE_TEXTS = 127
OPERATIONAL = 112
NO_STRUCTURE = 37
IMPORTED = 84
REFERENCES = ("awesome-mcp-servers", "github-mcp-server")


def baseline() -> tuple[dict, dict, list]:
    """A seal, a run report and a registry shaped exactly like the committed ones."""
    carried_operational = [f"factory-{index:03d}" for index in range(111)]
    operational_blueprint = ["servers"]                        # defines stages, carries no content
    blueprint_empty = [f"blueprint-{index:03d}" for index in range(35)]
    references = list(REFERENCES)

    seal_factories = []
    for identifier in carried_operational + operational_blueprint + blueprint_empty + references:
        carried = identifier in carried_operational or identifier in references
        seal_factories.append({"id": identifier,
                               "license": "MIT" if carried else "NOASSERTION",
                               "content_redistributed": carried})
    report_factories = []
    for factory in seal_factories:
        operational = factory["id"] in carried_operational or factory["id"] == "servers"
        report_factories.append({"id": factory["id"],
                                 "license": factory["license"],
                                 "content_redistributed": factory["content_redistributed"],
                                 "status": "OPERATIONAL-PLAN" if operational else "NO-STRUCTURE"})
    seal = {"contents": {"factories": SEAL_COUNT, "content_redistributed": CARRIED,
                         "with_license_text": LICENCE_TEXTS,
                         "by_group": {"AI-focused factory": 23, "MCP tool server": 42,
                                      "imported factory": IMPORTED}},
            "factories": seal_factories}
    report = {"summary": {"operational_plan": OPERATIONAL, "no_structure": NO_STRUCTURE,
                          "carried_without_definitions": 2, "stages_defined": 592,
                          "workers_defined": 579},
              "factories": report_factories}
    registry = [{"id": f"imported-{index:03d}", "license": "MIT"} for index in range(IMPORTED)]
    return seal, report, registry


class JudgeTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def judge(self, seal=None, report=None, registry=None):
        seal, report, registry = (seal or baseline()[0], report or baseline()[1],
                                 registry or baseline()[2])
        seal_path = self.root / "SEAL.json"
        report_path = self.root / "RUN-REPORT.json"
        registry_path = self.root / "imported_factories.json"
        seal_path.write_text(json.dumps(seal), encoding="utf-8")
        report_path.write_text(json.dumps(report), encoding="utf-8")
        registry_path.write_text(json.dumps(registry), encoding="utf-8")
        return JUDGE.judge(report_path, seal_path, registry_path)

    def test_the_committed_set_is_accepted_and_its_ledger_floor_is_printed(self) -> None:
        problems, warnings, evidence = self.judge()
        self.assertEqual([], problems)
        self.assertEqual([], warnings)
        self.assertIn("149 records (112 operational, 37 without definitions)", evidence[0])
        self.assertIn("ledger floor: 149 records, 112 operational, 84 imported", evidence[1])

    def test_a_set_that_shrank_is_rejected(self) -> None:
        seal, report, registry = baseline()
        seal["factories"] = seal["factories"][:-1]
        seal["contents"]["factories"] = SEAL_COUNT - 1
        seal["contents"]["by_group"]["imported factory"] = IMPORTED - 1
        problems, _, _ = self.judge(seal=seal, report=report, registry=registry)
        self.assertTrue(any("below the 149" in problem for problem in problems), problems)

    def test_operational_falling_below_the_ledger_is_rejected(self) -> None:
        """Losing a definition upstream is not growth, and must not be reportable as success."""
        seal, report, registry = baseline()
        demoted = next(factory for factory in report["factories"]
                       if factory["status"] == "OPERATIONAL-PLAN")
        demoted["status"] = "NO-STRUCTURE"
        report["summary"]["operational_plan"] = OPERATIONAL - 1
        report["summary"]["no_structure"] = NO_STRUCTURE + 1
        problems, _, _ = self.judge(seal=seal, report=report, registry=registry)
        self.assertTrue(any("below the 112" in problem for problem in problems), problems)

    def test_content_stripped_from_a_record_is_rejected(self) -> None:
        """A relicence upstream can strip content the set used to carry; that is not a grown set."""
        seal, report, registry = baseline()
        stripped = next(factory for factory in seal["factories"]
                        if factory["content_redistributed"] and factory["license"] == "MIT")
        stripped["content_redistributed"] = False
        stripped["license"] = "NOASSERTION"
        seal["contents"]["content_redistributed"] = CARRIED - 1
        for factory in report["factories"]:
            if factory["id"] == stripped["id"]:
                factory["content_redistributed"] = False
                factory["license"] = "NOASSERTION"
        problems, _, _ = self.judge(seal=seal, report=report, registry=registry)
        self.assertTrue(any("below the 113" in problem for problem in problems), problems)

    def test_a_missing_licence_text_warns_without_failing(self) -> None:
        seal, report, registry = baseline()
        seal["contents"]["with_license_text"] = LICENCE_TEXTS - 1
        problems, warnings, _ = self.judge(seal=seal, report=report, registry=registry)
        self.assertEqual([], problems)
        self.assertTrue(any("licence texts" in warning for warning in warnings), warnings)

    def test_a_registry_record_with_no_slice_is_rejected(self) -> None:
        """A record the sealer never sees is a silent absence, not a smaller set."""
        registry = baseline()[2] + [{"id": "imported-extra", "license": "MIT"}]
        problems, _, _ = self.judge(registry=registry)
        self.assertTrue(any("a record with no slice, or a slice with no record" in problem
                            for problem in problems), problems)

    def test_content_under_a_licence_the_seal_refuses_is_rejected(self) -> None:
        seal, report, registry = baseline()
        offender = next(factory for factory in seal["factories"]
                        if factory["content_redistributed"] and factory["license"] == "MIT")
        offender["license"] = "GPL-3.0"
        problems, _, _ = self.judge(seal=seal, report=report, registry=registry)
        self.assertTrue(any("under a licence the seal does not accept" in problem
                            for problem in problems), problems)

    def test_a_third_carried_record_that_defines_nothing_is_rejected(self) -> None:
        """Two records carry a README and define nothing; a third is a gap, not a precedent."""
        seal, report, registry = baseline()
        # A blueprint record: nothing carried, and the run found no structure in it. Flipping it to
        # "carried" makes a third record that carries content and defines nothing - the case the judge
        # must refuse. Picking it by shape rather than by position matters: the first record with no
        # content and no licence is `servers`, which defines stages, so flipping that one would only
        # trip the summary-agreement check and this test would pass for the wrong reason.
        emptied = next(factory for factory in seal["factories"]
                       if factory["id"].startswith("blueprint-"))
        emptied["content_redistributed"] = True
        emptied["license"] = "MIT"
        for factory in report["factories"]:
            if factory["id"] == emptied["id"]:
                factory["content_redistributed"] = True
                factory["license"] = "MIT"
        seal["contents"]["content_redistributed"] = CARRIED + 1
        report["summary"]["carried_without_definitions"] = 3
        problems, _, _ = self.judge(seal=seal, report=report, registry=registry)
        self.assertTrue(any(emptied["id"] in problem and "define no stages" in problem
                            for problem in problems), problems)

    def test_a_reference_record_that_now_defines_something_is_only_a_warning(self) -> None:
        """The allowance is data-driven: once a record stops needing it, say so - do not fail."""
        seal, report, registry = baseline()
        for factory in report["factories"]:
            if factory["id"] == "github-mcp-server":
                factory["status"] = "OPERATIONAL-PLAN"
        report["summary"]["operational_plan"] = OPERATIONAL + 1
        report["summary"]["no_structure"] = NO_STRUCTURE - 1
        report["summary"]["carried_without_definitions"] = 1
        problems, warnings, _ = self.judge(seal=seal, report=report, registry=registry)
        self.assertEqual([], problems)
        self.assertTrue(any("github-mcp-server" in warning for warning in warnings), warnings)

    def test_a_summary_that_disagrees_with_its_own_records_is_rejected(self) -> None:
        seal, report, registry = baseline()
        report["summary"]["operational_plan"] = OPERATIONAL - 2
        problems, _, _ = self.judge(seal=seal, report=report, registry=registry)
        self.assertTrue(any("while the records it lists hold" in problem for problem in problems),
                        problems)

    def test_a_seal_and_a_run_that_describe_different_sets_are_rejected(self) -> None:
        seal, report, registry = baseline()
        report["factories"] = report["factories"][:-1]
        report["summary"]["no_structure"] = NO_STRUCTURE - 1
        problems, _, _ = self.judge(seal=seal, report=report, registry=registry)
        self.assertTrue(any("disagree on the set" in problem for problem in problems), problems)

    def test_a_run_that_defined_fewer_stages_warns_without_failing(self) -> None:
        seal, report, registry = baseline()
        report["summary"]["stages_defined"] = 591
        problems, warnings, _ = self.judge(seal=seal, report=report, registry=registry)
        self.assertEqual([], problems)
        self.assertTrue(any("591" in warning and "stages" in warning for warning in warnings), warnings)

    def test_an_unreadable_report_is_rejected_rather_than_crashing(self) -> None:
        seal, report, registry = baseline()
        self.judge()  # the fixture is sound; now break only the report
        (self.root / "RUN-REPORT.json").write_text("{ not json", encoding="utf-8")
        problems, _, _ = JUDGE.judge(self.root / "RUN-REPORT.json", self.root / "SEAL.json",
                                     self.root / "imported_factories.json")
        self.assertEqual(1, len(problems))
        self.assertIn("could not be read as JSON", problems[0])

    def test_a_grown_set_is_accepted_and_reports_what_it_gained(self) -> None:
        """Growth is the point: the ledger is a floor, so adding to it must pass and be visible."""
        seal, report, registry = baseline()
        seal["factories"].append({"id": "factory-new", "license": "MIT",
                                  "content_redistributed": True})
        seal["contents"]["factories"] = SEAL_COUNT + 1
        seal["contents"]["content_redistributed"] = CARRIED + 1
        seal["contents"]["by_group"]["imported factory"] = IMPORTED + 1
        report["factories"].append({"id": "factory-new", "license": "MIT",
                                    "content_redistributed": True, "status": "OPERATIONAL-PLAN"})
        report["summary"]["operational_plan"] = OPERATIONAL + 1
        registry = registry + [{"id": "imported-new", "license": "MIT"}]
        problems, warnings, evidence = self.judge(seal=seal, report=report, registry=registry)
        self.assertEqual([], problems)
        self.assertEqual([], warnings)
        self.assertTrue(any("grown since the ledger: +1 records" in line for line in evidence),
                        evidence)


class VendoringPlanTest(unittest.TestCase):
    """The plan decides from the records, never from the calendar or from optimism."""

    def test_a_record_with_no_slice_needs_vendoring(self) -> None:
        self.assertTrue(VENDOR_NEEDED.needs_vendoring({"license": "MIT"}, None))

    def test_a_slice_fetched_under_a_different_licence_needs_vendoring(self) -> None:
        # This is the widened-record case: the registry now says MIT, the slice still says NOASSERTION.
        entry = {"id": "widened", "license": "MIT"}
        self.assertTrue(VENDOR_NEEDED.needs_vendoring(entry, {"id": "widened",
                                                              "license": "NOASSERTION"}))

    def test_a_slice_that_already_matches_needs_nothing(self) -> None:
        entry = {"id": "settled", "license": "Apache-2.0"}
        self.assertFalse(VENDOR_NEEDED.needs_vendoring(entry, {"id": "settled",
                                                               "license": "Apache-2.0"}))

    def test_both_registry_shapes_are_read(self) -> None:
        self.assertEqual([{"id": "a"}], VENDOR_NEEDED.entries_of([{"id": "a"}]))
        self.assertEqual([{"id": "b"}], VENDOR_NEEDED.entries_of({"factories": [{"id": "b"}]}))
        self.assertEqual([{"id": "c"}], VENDOR_NEEDED.entries_of({"servers": [{"id": "c"}]}))
        self.assertEqual([], VENDOR_NEEDED.entries_of({"nothing": "here"}))

    def test_the_three_registries_are_all_covered(self) -> None:
        kinds = {kind for kind, _, _ in VENDOR_NEEDED.REGISTRIES}
        self.assertEqual({"imported", "ai", "mcp"}, kinds)
        for kind, registry, directory in VENDOR_NEEDED.REGISTRIES:
            with self.subTest(kind):
                self.assertTrue(registry.is_file(), f"{registry} does not exist")
                self.assertTrue((REPO_ROOT / "sealed-factories" / directory).is_dir())


class WiringTest(unittest.TestCase):
    def test_the_script_calls_the_judge_and_honours_its_status(self) -> None:
        script = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("tools/ci/factory_growth_judge.py", script)
        self.assertIn("JUDGE_STATUS=$?", script)
        self.assertIn('if [ "$JUDGE_STATUS" -ne 0 ]', script)
        for forbidden in ("|| true", "continue-on-error", "set +e"):
            self.assertNotIn(forbidden, script)

    def test_the_script_refuses_a_scan_it_could_not_complete_and_a_record_with_no_slice(self) -> None:
        script = SCRIPT.read_text(encoding="utf-8")
        # A rate-limited scan leaves records untouched; that is safe but incomplete, so it must fail.
        self.assertIn("--fail-on-unreadable", script)
        # Vendoring is planned from the records, and re-checked after it runs: a record with no slice
        # would otherwise be absent from the seal without anything failing.
        self.assertEqual(2, script.count("tools/factory/vendor_needed.py"))
        self.assertIn("would be silently absent", script)

    def test_the_workflow_verifies_before_it_commits_and_only_then_dispatches_the_belt(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("bash tools/ci/factory_growth.sh", workflow)
        verify = workflow.index("Verify the set the growth produced")
        commit = workflow.index("Commit what the growth changed")
        dispatch = workflow.index("Dispatch the belt on the commit this job pushed")
        self.assertLess(verify, commit, "the set must be verified before it is committed")
        self.assertLess(commit, dispatch, "the belt is dispatched on what was committed")
        # The suite that decides whether the set may be committed is run - the whole of shell/tests, not
        # a hand-picked subset that could drift from what the belt runs - and a failure names the tests
        # that failed, because a hosted runner's raw log is not always retrievable afterwards.
        self.assertIn('python -m unittest discover -s shell/tests >"$log" 2>&1', workflow)
        self.assertIn("the shell test suite failed on the set this run produced", workflow)
        self.assertIn("::error title=Factory growth::- ${line}", workflow)
        # Formatting is the repository's own formatter run on the runner (node lives there), and the
        # check is still a check: if anything outside the paths this job commits is unformatted, the
        # job fails and names it rather than only saying the style check failed.
        self.assertIn("npm run format\n", workflow)
        self.assertIn("npm run format:check", workflow)
        self.assertIn("npx prettier --list-different .", workflow)
        # A machine commit cannot trigger workflows (GitHub's rule), so this job asks for the belt.
        self.assertIn("gh workflow run quality-conveyor-belt.yml", workflow)
        for forbidden in ("continue-on-error", "|| true"):
            self.assertNotIn(forbidden, workflow)
        # It writes; it must say so.
        self.assertIn("contents: write", workflow)
        self.assertIn("actions: write", workflow)

    def test_the_growth_workflow_does_not_touch_the_air_gapped_job(self) -> None:
        """The offline proof stays the thing it was; growth must not reach into it."""
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn("airgap_run.sh", workflow)
        self.assertNotIn("SEALED_NO_NETWORK", workflow)

    def test_the_machine_commit_obeys_the_repository_commit_message_rules(self) -> None:
        """The bot goes through the same hook a person does, and commitlint refuses a long body line."""
        workflow = WORKFLOW.read_text(encoding="utf-8")
        block = workflow.split("Commit what the growth changed", 1)[1].split(
            "Push the growth commit", 1)[0]
        messages = re.findall(r'-m "([^"]*)"', block)
        self.assertGreaterEqual(len(messages), 2, "the commit needs a subject and a body")
        for line in messages:
            self.assertLessEqual(len(line), 100,
                                 f"commitlint refuses a body line over 100 characters: {line!r}")
        # The type has to be one the repository's conventional-commit config allows.
        config = (REPO_ROOT / "commitlint.config.js").read_text(encoding="utf-8")
        self.assertIn("config-conventional", config)
        commit_type = messages[0].split(":", 1)[0].split("(", 1)[0]
        self.assertIn(commit_type, ("build", "chore", "ci", "docs", "feat", "fix", "perf",
                                    "refactor", "revert", "style", "test"))
        # A commit or a push that is refused says why: the first version of this step failed into the
        # job log on its own, and the reason never reached the repository.
        self.assertIn("the growth commit was refused by the repository", workflow)
        self.assertIn("could not be pushed", workflow)
        self.assertIn("steps.commit.outputs.committed == 'true'", workflow)
        self.assertIn("steps.push.outputs.pushed == 'true'", workflow)

    def test_the_commit_stage_is_limited_to_what_the_growth_owns(self) -> None:
        """A machine commit that stages everything is a machine commit that can commit anything."""
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("git add sealed-factories tools/factory docs artifacts", workflow)
        self.assertNotIn("git add -A", workflow)
        self.assertNotIn("git commit -a", workflow)
        # The formatter rewrites the whole tree; the commit must not.
        self.assertIn("npm run format\n", workflow)
        staged = workflow.split("git add ", 1)[1].split("\n", 1)[0].split()
        self.assertEqual(["sealed-factories", "tools/factory", "docs", "artifacts"], staged,
                         "the machine commit stages exactly these paths, compared as whole paths: a "
                         "substring check finds `factories` inside `sealed-factories` and says nothing")


if __name__ == "__main__":
    unittest.main(verbosity=2)
