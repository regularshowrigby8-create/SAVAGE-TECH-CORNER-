"""Tests for the discovery pass that grew the catalogued set from 72 to 87 factories.

The pass exists to answer one question per repository - does it publish a factory shape, under a licence
this component may carry? - and the expensive way to get it wrong is to say yes to something that only
*looks* like a factory. One candidate in the first sweep (`teng-lin/notebooklm-py`) matched the word
list through a utility module and a test directory, was sealed, and then defined nothing: the runtime
read zero stages, workers, gates and prompts from its 25 files. The checks below are what stops that
from happening quietly again.
"""

import json
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools" / "factory"))

import discover_factories  # noqa: E402

REGISTRIES = ("tools/factory/imported_factories.json",
              "tools/factory/imported_ai_factories.json",
              "tools/factory/imported_mcp_servers.json")

FACTORY_TREE = [
    "README.md",
    "agents/reviewer.md",
    "agents/planner.md",
    "skills/tdd/SKILL.md",
    "stages/build/README.md",
    "pipelines/release.yml",
    "src/factory.py",
    "tests/test_factory.py",
    ".github/workflows/ci.yml",
]

#: The shape that fooled the first sweep: plausible words, no definitions.
LIBRARY_TREE = [
    "README.md",
    "docs/steps.md",
    "src/steps.py",
    "src/orchestration.py",
    "src/factory_naming.py",
    "tests/test_steps.py",
    "tests/test_verification.py",
    ".github/workflows/test.yml",
]


class ShapeTest(unittest.TestCase):
    def test_a_repository_that_defines_agents_and_stages_qualifies(self):
        shape = discover_factories.evaluate_tree(FACTORY_TREE)
        self.assertIsNotNone(shape)
        self.assertGreaterEqual(shape["evidence_count"], 4)
        self.assertTrue(shape["ai_worker_evidence"])
        self.assertTrue(shape["definitions"], "the qualification has to name the proving paths")

    def test_a_library_that_only_uses_the_words_is_refused(self):
        # Every characteristic below is matched by *some* path; none of them is a definition.
        self.assertIsNone(discover_factories.evaluate_tree(LIBRARY_TREE))

    def test_a_thin_tree_is_refused_even_with_a_definition(self):
        paths = ["agents/reviewer.md", "README.md"]
        self.assertIsNone(discover_factories.evaluate_tree(paths))

    def test_the_evidence_recorded_is_the_specific_characteristics(self):
        shape = discover_factories.evaluate_tree(FACTORY_TREE)
        self.assertLessEqual(set(shape["evidence"]), {name for name, _ in
                                                      discover_factories.CHARACTERISTICS})

    def test_only_definition_bearing_paths_are_recorded_as_definitions(self):
        shape = discover_factories.evaluate_tree(FACTORY_TREE + ["src/util.py"])
        self.assertNotIn("src/util.py", shape["definitions"])

    def test_ci_configuration_alone_is_not_a_definition(self):
        # Every repository has a workflow file. Counting one as a factory definition is how a testing
        # library (schemathesis) and a security scanner (zaproxy) were briefly catalogued as factories.
        paths = ["README.md", "docs/steps.md", "src/steps.py", "src/orchestration.py",
                 "src/factory.py", "tests/test_verification.py", ".github/workflows/ci.yml"]
        self.assertIsNone(discover_factories.evaluate_tree(paths))

    def test_a_stage_directory_that_is_not_ci_still_counts(self):
        paths = LIBRARY_TREE + ["stages/build/README.md"]
        shape = discover_factories.evaluate_tree(paths)
        self.assertIsNotNone(shape)
        self.assertIn("stages/build/README.md", shape["definitions"])


class LicenceNamingTest(unittest.TestCase):
    """GitHub upper-cases some SPDX ids; the registry has to hold one canonical spelling."""

    def test_an_upper_cased_api_answer_becomes_the_canonical_name(self):
        self.assertEqual(discover_factories.canonical_licence("APACHE-2.0"), "Apache-2.0")
        self.assertEqual(discover_factories.canonical_licence("MIT"), "MIT")
        self.assertEqual(discover_factories.canonical_licence("AGPL-3.0"), "AGPL-3.0")

    def test_an_unknown_licence_keeps_a_readable_name_and_is_never_permissive(self):
        self.assertEqual(discover_factories.canonical_licence("NOASSERTION"), "NOASSERTION")
        self.assertNotIn(discover_factories.canonical_licence("Some Custom Licence"),
                         discover_factories.PERMISSIVE_LICENCES)

    def test_the_registry_holds_no_upper_cased_licence_names(self):
        # The first plan import wrote `APACHE-2.0`, which is permissive but did not string-match the
        # permissive set, so a carriable repository was recorded as a blueprint. That cannot recur.
        for relative in REGISTRIES:
            payload = json.loads((REPO_ROOT / relative).read_text(encoding="utf-8"))
            if isinstance(payload, dict):
                payload = payload.get("factories") or payload.get("servers") or []
            for entry in payload:
                with self.subTest(f'{relative}:{entry["id"]}'):
                    licence = entry["license"]
                    self.assertEqual(discover_factories.canonical_licence(licence), licence)


class PlanImportTest(unittest.TestCase):
    """The nine repositories a plan named and never imported are accounted for, one by one."""

    def plan(self):
        return json.loads((REPO_ROOT / "tools" / "factory" / "repositories.json").read_text(
            encoding="utf-8"))["planned"]

    def outcomes(self):
        report = json.loads((REPO_ROOT / "tools" / "factory" / "plan_import.json")
                            .read_text(encoding="utf-8"))
        return {entry["tool"]: entry for entry in report["outcomes"]}

    def test_every_planned_repository_has_a_recorded_outcome(self):
        # The plan named nine repositories and for a long time imported none of them. Importing is not
        # the only acceptable outcome - refusing is - but leaving one unaccounted for is not.
        outcomes = self.outcomes()
        for item in self.plan():
            with self.subTest(item["tool"]):
                self.assertIn(item["tool"], outcomes, f'{item["tool"]} has no recorded outcome')
                outcome = outcomes[item["tool"]]
                self.assertIn(outcome["outcome"], ("imported", "refused"))
                self.assertTrue(outcome["reason"])

    def test_an_imported_plan_repository_is_in_the_registry_and_a_refusal_is_not(self):
        registry = json.loads((REPO_ROOT / "tools" / "factory" / "imported_factories.json")
                              .read_text(encoding="utf-8"))
        ids = {entry["id"] for entry in registry}
        for entry in self.outcomes().values():
            with self.subTest(entry["tool"]):
                if entry["outcome"] == "imported":
                    self.assertIn(entry["id"], ids)
                    self.assertTrue(entry["license"])
                else:
                    self.assertNotIn(entry.get("id"), ids)

    def test_the_imported_plan_records_say_why_they_were_wanted(self):
        registry = json.loads((REPO_ROOT / "tools" / "factory" / "imported_factories.json")
                              .read_text(encoding="utf-8"))
        planned = [entry for entry in registry if entry.get("planned_tool")]
        self.assertTrue(planned, "the plan's repositories were imported; none records its provenance")
        for entry in planned:
            with self.subTest(entry["id"]):
                self.assertTrue(entry["handles"], f'{entry["id"]} does not say what it handles')
                self.assertTrue(entry["plan_gap"], f'{entry["id"]} does not say which gap it closes')

    def test_an_agpl_repository_from_the_plan_is_a_blueprint_not_content(self):
        # Renovate is AGPL-3.0: it may be described, never copied.
        registry = json.loads((REPO_ROOT / "tools" / "factory" / "imported_factories.json")
                              .read_text(encoding="utf-8"))
        seal = json.loads((REPO_ROOT / "sealed-factories" / "SEAL.json").read_text(encoding="utf-8"))
        carried = {factory["id"] for factory in seal["factories"] if factory["content_redistributed"]}
        copyleft = [entry for entry in registry
                    if entry["license"] in ("AGPL-3.0", "GPL-3.0", "GPL-2.0", "LGPL-3.0")]
        self.assertTrue(copyleft, "the catalogued set holds copyleft repositories; none was found")
        for entry in copyleft:
            with self.subTest(entry["id"]):
                self.assertEqual(entry["import_class"], "blueprint-only")
                self.assertNotIn(entry["id"], carried,
                                 f'{entry["id"]} is under {entry["license"]} but its content is sealed')


if __name__ == "__main__":
    unittest.main()
