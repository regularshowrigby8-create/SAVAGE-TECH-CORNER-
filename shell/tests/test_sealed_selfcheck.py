"""Tests for the sealed component rechecking itself - and for the sentences it is allowed to say.

A self-check that reports "functional" is the strongest claim in this round, so most of these tests are
written from the direction of refusing it: a payload that changed, a licence text that is gone, a
registry record with no slice, a module the seal does not pin, a seal that disagrees with its own
records. Each must be a blocking finding, not a footnote. Three more pin the properties that make the
claim honest:

  * fitness is judged with the run's own reader, imported rather than reimplemented - and each repair is
    assigned to the functional factory whose own definitions name that capability, with an empty
    assignment when none does, because crediting work to a factory that never claimed it is the thing
    this module exists not to do;
  * the repairs are the component's own tools and nothing else: a repair that needs the network refuses
    while SEALED_NO_NETWORK=1 is set, and no tool is ever run with the network under pretence;
  * the two things that are not defects - content withheld under the licence wall, and a slice that
    defines nothing to run - are reported as named states and are never counted as fitness.
"""

import json
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools" / "factory"))
sys.path.insert(0, str(REPO_ROOT / "sealed-factories" / "runtime"))

import selfcheck  # noqa: E402


class FakeStructure:
    """Stand-in for the component's reader: a survey takes its structures, it does not build them."""

    def __init__(self, structures: dict):
        self.structures = structures

    def __call__(self, _document, _factory):
        raise AssertionError("survey with explicit structures must not call the reader")


def make_payload(path: Path, files: dict[str, str]) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(path, "w:gz") as archive:
        for name, text in files.items():
            data = text.encode("utf-8")
            info = tarfile.TarInfo(name)
            info.size = len(data)
            import io
            archive.addfile(info, io.BytesIO(data))
    import hashlib
    return hashlib.sha256(path.read_bytes()).hexdigest()


class SelfCheckCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.sealed = Path(self.temp.name)
        self.store = self.sealed / "licenses"
        self.store.mkdir()
        self.payload_note = self.sealed / "payloads"
        self.payload_note.mkdir()

    def record(self, identifier: str, *, carried: bool = True, licence: str = "MIT",
               licence_file: str | None = None, structure: bool = True) -> dict:
        payload = self.payload_note / f"{identifier}.tar.gz"
        digest = make_payload(payload, {"stages.yaml": "stages:\n  - build\n"} if structure else {
            "README.md": "there is no pipeline here, only prose\n"})
        return {"id": identifier, "license": licence, "content_redistributed": carried,
                "license_present": bool(licence_file), "license_file": licence_file,
                "payload": f"payloads/{payload.name}", "payload_sha256": digest,
                "payload_bytes": payload.stat().st_size,
                "group": "imported factory", "url": "https://github.com/example/x"}

    def survey(self, records: list[dict], structures: dict | None = None,
               contents: dict | None = None) -> dict:
        document = {"factories": records, "contents": contents or {}}
        # `sealed_root` is this test's own tree: without it the survey resolved payloads against the
        # repository's real sealed set and reported every fixture payload as missing - a test that
        # failed for the fixture's sake rather than the code's.
        return selfcheck.survey(document, structures=structures or {}, sealed_root=self.sealed,
                                store=self.store, index_path=self.sealed / "LICENSES.json",
                                offline_now=True)


class FindingTest(SelfCheckCase):
    def test_a_payload_that_is_gone_blocks(self) -> None:
        record = self.record("alpha")
        (self.payload_note / "alpha.tar.gz").unlink()
        survey = self.survey([record], {"alpha": {"structure_found": True, "labels": []}})
        codes = [finding["code"] for finding in survey["findings"]]
        self.assertIn("payload-missing", codes)
        self.assertTrue(all(finding["blocks"] for finding in survey["findings"]))

    def test_a_payload_that_changed_blocks(self) -> None:
        record = self.record("alpha")
        self.payload_note.joinpath("alpha.tar.gz").write_bytes(b"not the payload the seal hashed")
        survey = self.survey([record], {"alpha": {"structure_found": True, "labels": []}})
        self.assertIn("payload-hash-mismatch", [finding["code"] for finding in survey["findings"]])

    def test_a_missing_licence_text_blocks(self) -> None:
        record = self.record("alpha", licence_file="alpha-LICENSE")
        survey = self.survey([record], {"alpha": {"structure_found": True, "labels": []}})
        self.assertIn("licence-text-missing", [finding["code"] for finding in survey["findings"]])

    def test_a_stale_licence_index_blocks(self) -> None:
        record = self.record("alpha", licence_file="alpha-LICENSE")
        (self.store / "alpha-LICENSE").write_text("MIT License\n", encoding="utf-8")
        survey = self.survey([record], {"alpha": {"structure_found": True, "labels": []}})
        self.assertIn("licence-index-stale", [finding["code"] for finding in survey["findings"]],
                      "a store with no current index is not a store anything can trust")

    def test_a_seal_count_that_disagrees_with_its_records_blocks(self) -> None:
        record = self.record("alpha")
        survey = self.survey([record], {"alpha": {"structure_found": True, "labels": []}},
                             contents={"factories": 9, "content_redistributed": 0})
        codes = [finding["code"] for finding in survey["findings"]]
        self.assertIn("seal-count-disagreement", codes)

    def test_a_module_in_the_surveyed_tree_that_the_seal_does_not_pin_blocks(self) -> None:
        """The check runs against the tree being surveyed - which is why it is a parameter."""
        record = self.record("alpha")
        (self.sealed / "runtime").mkdir(exist_ok=True)
        (self.sealed / "runtime" / "extra.py").write_text("VALUE = 1\n", encoding="utf-8")
        survey = self.survey([record], {"alpha": {"structure_found": True, "labels": []}})
        unpinned = [finding for finding in survey["findings"]
                    if finding["code"] == "component-file-unpinned"]
        self.assertTrue(unpinned, [finding["code"] for finding in survey["findings"]])
        self.assertIn("runtime/extra.py", unpinned[0]["detail"])
        self.assertTrue(unpinned[0]["blocks"])

    def test_an_unreadable_slice_blocks_rather_than_being_called_empty(self) -> None:
        record = self.record("alpha")
        survey = self.survey([record], {"alpha": {"structure_found": False, "labels": [],
                                                 "unreadable": True}})
        self.assertIn("component-incomplete", [finding["code"] for finding in survey["findings"]])

    def test_a_sound_payload_and_licence_are_not_findings(self) -> None:
        record = self.record("alpha", licence_file="alpha-LICENSE")
        (self.store / "alpha-LICENSE").write_text("MIT License\n", encoding="utf-8")
        survey = selfcheck.survey(
            {"factories": [record], "contents": {"factories": 1, "content_redistributed": 1,
                                                 "with_license_text": 1,
                                                 "component_files": [{"path": "runtime/toolkit.py",
                                                                      "sha256": "x"}]}},
            structures={"alpha": {"structure_found": True, "labels": ["stage: build"]}},
            store=self.store, index_path=self.sealed / "LICENSES.json", offline_now=True)
        # The index is re-derived through the component's own store tool, so this copy must be able to
        # import it; when it cannot, that is the finding - and it is not silently called clean.
        index_findings = [finding for finding in survey["findings"]
                          if finding["code"] == "licence-index-stale"]
        self.assertEqual([], [finding for finding in survey["findings"]
                              if finding["code"] in ("payload-missing", "payload-hash-mismatch",
                                                     "licence-text-missing")])
        self.assertLessEqual(len(index_findings), 1)


class StateTest(SelfCheckCase):
    def test_withheld_content_is_a_named_state_and_never_fitness(self) -> None:
        record = self.record("blueprint", carried=False, licence="NOASSERTION")
        survey = self.survey([record], {"blueprint": {"structure_found": True,
                                                     "labels": ["operator: reviewer"]}})
        self.assertEqual([], [state for state in survey["states"]
                              if state["code"] == "carried-without-definitions"])
        self.assertIn("content-withheld", [state["code"] for state in survey["states"]])

    def test_a_carried_slice_that_defines_nothing_is_a_named_state(self) -> None:
        record = self.record("empty", structure=False)
        survey = self.survey([record], {"empty": {"structure_found": False, "labels": []}})
        self.assertIn("carried-without-definitions", [state["code"] for state in survey["states"]])
        self.assertEqual([], [finding for finding in survey["findings"]
                              if finding["record"] == "empty" and finding["blocks"]])


class AssignmentTest(SelfCheckCase):
    def finding(self, code: str, record: str = "alpha") -> dict:
        return {"code": code, "record": record, "repairable": True, "blocks": True, "detail": ""}

    def test_the_repair_goes_to_the_factory_whose_definitions_name_the_capability(self) -> None:
        structures = {"licences": {"structure_found": True,
                                   "labels": ["stage: licence compliance check"]},
                      "other": {"structure_found": True, "labels": ["stage: build"]}}
        assigned = selfcheck.assign(self.finding("licence-text-missing"), structures)
        self.assertEqual("licences", assigned["assigned_to"])
        self.assertIn("licen", assigned["assignment"])
        self.assertTrue(assigned["assign_reason"])

    def test_a_finding_no_functional_record_names_stays_unassigned(self) -> None:
        structures = {"other": {"structure_found": True, "labels": ["stage: build"]}}
        assigned = selfcheck.assign(self.finding("licence-text-missing"), structures)
        self.assertIsNone(assigned["assigned_to"])
        self.assertEqual("unassigned", assigned["assignment"])

    def test_a_record_that_defines_nothing_cannot_own_a_repair(self) -> None:
        structures = {"licences": {"structure_found": False, "labels": ["stage: licence check"]}}
        assigned = selfcheck.assign(self.finding("licence-text-missing"), structures)
        self.assertIsNone(assigned["assigned_to"],
                          "a slice that defines nothing cannot be said to do the work")

    def test_the_assignment_is_deterministic(self) -> None:
        structures = {f"f{index}": {"structure_found": True, "labels": ["stage: licence check"]}
                      for index in range(5)}
        first = selfcheck.assign(self.finding("licence-text-missing"), structures)
        second = selfcheck.assign(self.finding("licence-text-missing"), structures)
        self.assertEqual(first, second)
        self.assertEqual("f0", first["assigned_to"])

    def test_a_non_repairable_finding_is_not_assigned(self) -> None:
        assigned = selfcheck.assign({"code": "component-incomplete", "record": "-",
                                     "repairable": False, "reason": "a person has to restore it"},
                                    {"a": {"structure_found": True, "labels": ["licence"]}})
        self.assertIsNone(assigned["assigned_to"])
        self.assertEqual("not assignable", assigned["assignment"])


class RepairTest(SelfCheckCase):
    def test_a_network_repair_refuses_while_the_network_is_switched_off(self) -> None:
        ran: list[list[str]] = []
        result = selfcheck.repair({"code": "payload-missing", "record": "alpha"},
                                  runner=lambda argv, **_kwargs: ran.append(argv),
                                  offline_now=True)
        self.assertFalse(result["repaired"])
        self.assertTrue(result["refused"])
        self.assertEqual([], ran, "nothing may be executed when the repair needs the network and it is off")

    def test_a_repair_reports_what_ran_and_what_it_returned(self) -> None:
        class Done:
            returncode = 0
            stdout = "sealed\n"
            stderr = ""

        seen: list[list[str]] = []

        def runner(argv, **_kwargs):
            seen.append(argv)
            return Done()

        result = selfcheck.repair({"code": "component-file-unpinned", "record": "-"},
                                  runner=runner, offline_now=True)
        self.assertTrue(result["repaired"])
        self.assertEqual(2, len(seen), "the seal is written and then verified")
        self.assertTrue(seen[0][1].endswith("seal_factories.py"))
        self.assertEqual(["seal"], seen[0][2:])
        self.assertEqual(["verify"], seen[1][2:])

    def test_a_repair_chain_ends_with_a_reseal_whenever_it_changed_a_sealed_thing(self) -> None:
        """A repair that changed a payload or a stored text must re-seal, or it breaks what it fixed."""
        for code in ("payload-missing", "licence-text-missing", "licence-index-stale"):
            commands = selfcheck.repair_commands({"code": code, "record": "alpha"})
            tail = [command[1].rsplit("/", 1)[-1] for command in commands[-2:]]
            self.assertEqual(["seal_factories.py", "seal_factories.py"], tail, code)
            self.assertEqual(["seal", "verify"], [command[2] for command in commands[-2:]], code)

    def test_a_failing_repair_is_not_reported_as_repaired(self) -> None:
        class Failed:
            returncode = 1
            stdout = ""
            stderr = "no"

        result = selfcheck.repair({"code": "component-file-unpinned", "record": "-"},
                                  runner=lambda argv, **_kwargs: Failed(), offline_now=True)
        self.assertFalse(result["repaired"])
        self.assertIn("exited 1", result["detail"])

    def test_a_repair_needs_the_tool_it_names(self) -> None:
        result = selfcheck.repair({"code": "payload-missing", "record": "alpha"},
                                  runner=lambda argv, **_kwargs: None, offline_now=False)
        self.assertFalse(result["repaired"])
        self.assertIn("not present", result["detail"])


class VerdictTest(SelfCheckCase):
    def test_no_seal_is_its_own_verdict_and_not_a_pass(self) -> None:
        report = selfcheck.run(document=None, sealed_root=self.sealed, store=self.store,
                               index_path=self.sealed / "LICENSES.json", offline_now=True)
        self.assertEqual("NO-SEAL", report["verdict"])
        self.assertNotEqual("FUNCTIONAL", report["verdict"])

    def test_a_blocking_finding_that_survives_the_repair_is_not_functional(self) -> None:
        record = self.record("alpha")
        (self.payload_note / "alpha.tar.gz").unlink()          # nothing can restore this locally

        class Failed:
            returncode = 1
            stdout = ""
            stderr = "the slice could not be fetched"

        document = {"factories": [record], "contents": {}}
        report = selfcheck.run(do_repair=True, document=document, sealed_root=self.sealed,
                               store=self.store, index_path=self.sealed / "LICENSES.json",
                               offline_now=True, runner=lambda argv, **_kwargs: Failed())
        self.assertEqual("NOT FUNCTIONAL", report["verdict"])
        self.assertTrue(report["blocking"])
        self.assertEqual(record["id"], report["blocking"][0]["record"])
        self.assertFalse(report["repairs"][0]["repaired"],
                         "a repair whose command failed must not be reported as repaired")

    def test_the_survey_does_not_change_anything(self) -> None:
        record = self.record("alpha", licence_file="alpha-LICENSE")
        before = sorted(path.name for path in self.store.iterdir())
        self.survey([record], {"alpha": {"structure_found": True, "labels": []}})
        self.assertEqual(before, sorted(path.name for path in self.store.iterdir()))
        self.assertFalse((self.sealed / "LICENSES.json").exists())

    def test_the_component_reports_which_tool_is_missing_rather_than_assuming_itself_fit(self) -> None:
        missing = selfcheck.component_tools(missing_only=True)
        self.assertEqual({}, missing,
                         f"this checkout is missing {sorted(missing)}: the published component has to "
                         f"carry its own tools")
        for name, path in selfcheck.COMPONENT_TOOLS.items():
            self.assertTrue(path.is_file(), f"{name} ({path}) has to exist for the self-check to be "
                                            f"able to do anything about a finding it reports")


class PublishableTest(unittest.TestCase):
    def test_the_self_check_is_pinned_by_the_seal_and_by_the_agreement_test(self) -> None:
        """A module the seal does not pin is a module that can be swapped without verification."""
        sealer = (REPO_ROOT / "tools" / "factory" / "seal_factories.py").read_text(encoding="utf-8")
        checker = (REPO_ROOT / "sealed-factories" / "runtime" / "sealcheck.py").read_text(
            encoding="utf-8")
        self.assertIn('COMPONENT_DATA = ("mcp-servers.json", "LICENSES.json")', sealer)
        self.assertIn('COMPONENT_DATA = ("mcp-servers.json", "LICENSES.json")', checker)
        runtime = json.loads((REPO_ROOT / "sealed-factories" / "SEAL.json").read_text(
            encoding="utf-8")).get("component_files", [])
        paths = {entry["path"] for entry in runtime}
        self.assertIn("runtime/selfcheck.py", paths,
                      "the self-check has to be pinned by the seal it checks")

    def test_the_publish_script_cannot_reach_main(self) -> None:
        script = (REPO_ROOT / "tools" / "ci" / "publish_sealed_factory.sh").read_text(
            encoding="utf-8")
        self.assertIn('main|master|production)', script)
        self.assertIn("refusing to publish the component to", script)
        self.assertIn("PUBLISH_BRANCH", script)

    def test_the_publish_workflow_does_not_stage_the_repository(self) -> None:
        workflow = (REPO_ROOT / ".github" / "workflows" / "publish-sealed-factory.yml").read_text(
            encoding="utf-8")
        self.assertNotIn("git add -A", workflow)
        self.assertIn("selfcheck", workflow)
        self.assertIn("contents: write", workflow)


if __name__ == "__main__":
    unittest.main(verbosity=2)
