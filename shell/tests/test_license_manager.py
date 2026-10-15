"""Tests for the licence store: the index over the stored texts, the checks on it, and the import.

The store is the component's evidence for the licence wall - every carried factory has its licence text
beside it, and the index is pinned by the seal. So the dangerous mistakes are the ones that would make
the store *look* complete or *look* clean: a stale index after a text changed, an orphan nothing
references, a record carrying content with no licence to carry it under, an import that quietly skipped
a repository it could not read. Each of those has a test below, and so does the rule that this tool can
never widen the wall: it may fill in what is missing and it may not touch a verdict.
"""

import base64
import json
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "tools" / "factory"))

import license_manager  # noqa: E402
import license_text  # noqa: E402

MIT = """MIT License

Copyright (c) 2026 Example

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
"""

APACHE = """Apache License
Version 2.0, January 2004
http://www.apache.org/licenses/
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
"""


def sha(text: str) -> str:
    return license_manager.sha256_bytes(text.encode("utf-8"))


def store_with(*files: tuple[str, str]) -> tempfile.TemporaryDirectory:
    directory = tempfile.TemporaryDirectory()
    root = Path(directory.name)
    for name, text in files:
        (root / name).write_text(text, encoding="utf-8")
    return directory


def seal_with(records: list[dict]) -> Path:
    directory = tempfile.TemporaryDirectory()
    path = Path(directory.name) / "SEAL.json"
    path.write_text(json.dumps({"factories": records, "contents": {}}), encoding="utf-8")
    return path


class IndexTest(unittest.TestCase):
    def test_the_index_names_every_text_with_its_hash_and_classification(self) -> None:
        with store_with(("alpha-LICENSE", MIT), ("beta-LICENSE", APACHE)) as store:
            index = license_manager.build_index(Path(store))
            self.assertEqual(2, index["count"])
            entries = {entry["file"]: entry for entry in index["texts"]}
            self.assertEqual("MIT", entries["alpha-LICENSE"]["spdx"])
            self.assertEqual("Apache-2.0", entries["beta-LICENSE"]["spdx"])
            self.assertEqual(sha(MIT), entries["alpha-LICENSE"]["sha256"])
            self.assertEqual("alpha", entries["alpha-LICENSE"]["record"])
            self.assertEqual(len(MIT.encode()), entries["alpha-LICENSE"]["bytes"])

    def test_the_index_digest_is_the_entries_and_nothing_else(self) -> None:
        """A timestamp in the index would make two identical stores hash differently and re-seal forever."""
        with store_with(("alpha-LICENSE", MIT)) as store:
            first = license_manager.build_index(Path(store))
            third = license_manager.build_index(Path(store))
            self.assertEqual(first["index_sha256"], third["index_sha256"],
                             "two reads of one unchanged store must produce one digest")
            self.assertEqual(first["texts"], third["texts"])
            self.assertEqual(1, first["count"])

    def test_a_file_that_is_not_a_licence_is_not_indexed_and_is_reported(self) -> None:
        """A store that indexes anything is not a store of licences."""
        with store_with(("alpha-LICENSE", MIT), ("notes.txt", "not a licence")) as store:
            store_path = Path(store)
            index = license_manager.build_index(store_path)
            self.assertEqual(1, index["count"])
            self.assertEqual("alpha-LICENSE", index["texts"][0]["file"])
            license_manager.write_index(store_path / "LICENSES.json", store_path)
            seal = seal_with([{"id": "alpha", "license_file": "alpha-LICENSE",
                               "license_present": True, "content_redistributed": True}])
            problems = license_manager.verify(store_path, store_path / "LICENSES.json", seal)
            self.assertTrue(any("notes.txt" in problem and "not a licence text" in problem
                                for problem in problems), problems)


class VerifyTest(unittest.TestCase):
    def test_a_missing_index_is_a_problem(self) -> None:
        with store_with(("alpha-LICENSE", MIT)) as store:
            seal = seal_with([{"id": "alpha", "license_file": "alpha-LICENSE",
                               "license_present": True, "content_redistributed": True}])
            problems = license_manager.verify(Path(store), Path(store) / "nope.json", seal)
            self.assertTrue(any("no licence index" in problem for problem in problems), problems)

    def test_a_stale_index_is_a_problem(self) -> None:
        """The failure this whole tool exists for: a stored text changes and the index still says it did not."""
        with store_with(("alpha-LICENSE", MIT)) as store:
            store_path = Path(store)
            index = store_path / "LICENSES.json"
            license_manager.write_index(index, store_path)
            (store_path / "alpha-LICENSE").write_text(MIT + "\nand a term that was added later.\n",
                                                      encoding="utf-8")
            seal = seal_with([{"id": "alpha", "license_file": "alpha-LICENSE",
                               "license_present": True, "content_redistributed": True}])
            problems = license_manager.verify(store_path, index, seal)
            self.assertTrue(any("stale" in problem for problem in problems), problems)

    def test_an_orphan_text_is_a_problem(self) -> None:
        with store_with(("alpha-LICENSE", MIT), ("nobody-LICENSE", APACHE)) as store:
            store_path = Path(store)
            index = store_path / "LICENSES.json"
            license_manager.write_index(index, store_path)
            seal = seal_with([{"id": "alpha", "license_file": "alpha-LICENSE",
                               "license_present": True, "content_redistributed": True}])
            problems = license_manager.verify(store_path, index, seal)
            self.assertTrue(any("orphan" in problem for problem in problems), problems)

    def test_content_carried_without_a_licence_is_a_problem(self) -> None:
        """The wall itself: content may not travel without the licence that allows it to."""
        with store_with(("alpha-LICENSE", MIT)) as store:
            store_path = Path(store)
            index = store_path / "LICENSES.json"
            license_manager.write_index(index, store_path)
            seal = seal_with([{"id": "alpha", "license": "MIT", "license_file": None,
                               "license_present": False, "content_redistributed": True}])
            problems = license_manager.verify(store_path, index, seal)
            self.assertTrue(any("carries content with no licence text" in problem
                                for problem in problems), problems)

    def test_a_sealed_record_whose_text_is_gone_is_a_problem(self) -> None:
        with store_with() as store:
            store_path = Path(store)
            license_manager.write_index(store_path / "LICENSES.json", store_path)
            seal = seal_with([{"id": "alpha", "license_file": "alpha-LICENSE",
                               "license_present": True, "content_redistributed": True}])
            problems = license_manager.verify(store_path, store_path / "LICENSES.json", seal)
            self.assertTrue(any("not in the store" in problem for problem in problems), problems)

    def test_a_sound_store_verifies_clean(self) -> None:
        with store_with(("alpha-LICENSE", MIT)) as store:
            store_path = Path(store)
            license_manager.write_index(store_path / "LICENSES.json", store_path)
            seal = seal_with([{"id": "alpha", "license_file": "alpha-LICENSE",
                               "license_present": True, "content_redistributed": True}])
            problems = license_manager.verify(store_path, store_path / "LICENSES.json", seal)
            self.assertEqual([], problems)


class ImportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.store = self.root / "licenses"
        self.store.mkdir()
        self.index = self.root / "LICENSES.json"
        self.vendor = self.root / "vendor"
        self.vendor.mkdir()
        self.registry = self.root / "imported_factories.json"

    def write_registry(self, entries: list[dict]) -> None:
        self.registry.write_text(json.dumps(entries), encoding="utf-8")

    def run_import(self, fetch=None, **kwargs):
        """The tool reads the registries and the vendored records by module globals, so a test points
        both at its own tree: a fixture that quietly read the real sealed set would be testing nothing."""
        original = license_manager.REGISTRIES, license_manager.SEALED
        license_manager.REGISTRIES = (("imported", self.registry, self.vendor.name),)
        license_manager.SEALED = self.root
        try:
            return license_manager.import_missing(self.store, self.index, fetch=fetch or _Never,
                                                  **kwargs)
        finally:
            license_manager.REGISTRIES, license_manager.SEALED = original

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_a_text_the_record_carries_is_restored_without_any_network(self) -> None:
        """The component's own bytes first: a store repaired from what it already has needs no API."""
        (self.vendor / "alpha.json").write_text(json.dumps(
            {"id": "alpha", "license": "MIT", "_license_bytes": base64.b64encode(
                MIT.encode()).decode()}), encoding="utf-8")
        self.write_registry([{"id": "alpha", "license": "MIT", "license_path": "LICENSE"}])
        actions, problems = self.run_import()
        self.assertEqual([], problems)
        self.assertEqual(["restored-from-record"], [action["action"] for action in actions])
        self.assertTrue((self.store / "alpha-LICENSE").is_file())
        self.assertTrue(self.index.is_file(), "the index has to follow the store it indexes")
        self.assertEqual(MIT, (self.store / "alpha-LICENSE").read_text(encoding="utf-8"))

    def test_a_text_nobody_holds_is_fetched_from_the_repository(self) -> None:
        (self.vendor / "alpha.json").write_text(json.dumps(
            {"id": "alpha", "license": "MIT", "license_file": "LICENSE"}), encoding="utf-8")
        self.write_registry([{"id": "alpha", "full_name": "example/alpha", "license": "MIT",
                              "license_path": "LICENSE"}])
        actions, problems = self.run_import(fetch=_Fetcher(MIT))
        self.assertEqual([], problems, problems)
        self.assertEqual(["imported"], [action["action"] for action in actions])
        stored = json.loads((self.vendor / "alpha.json").read_text(encoding="utf-8"))
        self.assertEqual(sha(MIT), stored["license_text_sha256"])
        self.assertEqual("LICENSE", stored["license_file"])

    def test_an_import_never_touches_the_licence_verdict_or_the_wall(self) -> None:
        """A tool that could widen a record's licence would be a way around the wall, so it cannot."""
        # A record the wall does not accept, whose upstream file is fetched anyway: the text is stored
        # and indexed, and the verdict is still not this tool's to change.
        (self.vendor / "alpha.json").write_text(json.dumps(
            {"id": "alpha", "license": "NOASSERTION", "license_file": "LICENSE",
             "content_redistributed": False, "import_class": "blueprint-only"}), encoding="utf-8")
        self.write_registry([{"id": "alpha", "full_name": "example/alpha",
                              "license": "NOASSERTION", "license_path": "LICENSE"}])
        actions, problems = self.run_import(fetch=_Fetcher(MIT))
        self.assertEqual([], problems, problems)
        self.assertEqual(["imported"], [action["action"] for action in actions])
        self.assertEqual(MIT, (self.store / "alpha-LICENSE").read_text(encoding="utf-8"))
        stored = json.loads((self.vendor / "alpha.json").read_text(encoding="utf-8"))
        self.assertEqual("NOASSERTION", stored["license"],
                         "the licence verdict is the wall's business, not the store's")
        self.assertFalse(stored["content_redistributed"])
        script = (REPO_ROOT / "tools" / "factory" / "license_manager.py").read_text(encoding="utf-8")
        self.assertNotIn('record["license"] =', script)
        self.assertNotIn("record['license'] =", script)
        self.assertNotIn('record["content_redistributed"] =', script)

    def test_a_repository_that_cannot_be_read_is_a_problem_not_a_note(self) -> None:
        """A rate-limited or moved repository leaves a text nobody imported, and that must fail."""
        (self.vendor / "beta.json").write_text(json.dumps({"id": "beta", "license": "MIT"}),
                                               encoding="utf-8")
        self.write_registry([{"id": "beta", "full_name": "example/beta", "license": "MIT",
                              "license_path": "LICENSE"}])
        actions, problems = self.run_import(fetch=_Unreadable())
        self.assertTrue(problems, "an import it could not complete must be reported as a problem")
        self.assertEqual("unreadable", problems[0]["action"])
        self.assertNotIn("imported", [action["action"] for action in actions])
        self.assertFalse((self.store / "beta-LICENSE").exists())

    def test_a_repository_with_no_licence_file_is_recorded_as_exactly_that(self) -> None:
        (self.vendor / "gamma.json").write_text(json.dumps({"id": "gamma", "license": "MIT"}),
                                                encoding="utf-8")
        self.write_registry([{"id": "gamma", "full_name": "example/gamma", "license": "MIT",
                              "license_path": "LICENSE"}])
        actions, problems = self.run_import(fetch=_NoLicence())
        self.assertEqual([], problems)
        self.assertEqual(["no-licence-published"], [action["action"] for action in actions])
        self.assertFalse((self.store / "gamma-LICENSE").exists())

    def test_dry_run_writes_nothing(self) -> None:
        (self.vendor / "alpha.json").write_text(json.dumps(
            {"id": "alpha", "license": "MIT", "_license_bytes": base64.b64encode(
                MIT.encode()).decode()}), encoding="utf-8")
        self.write_registry([{"id": "alpha", "license": "MIT", "license_path": "LICENSE"}])
        _actions, problems = self.run_import(dry_run=True)
        self.assertEqual([], problems)
        self.assertFalse((self.store / "alpha-LICENSE").exists())
        self.assertFalse(self.index.exists())

    def test_a_registry_with_nothing_to_store_is_left_alone(self) -> None:
        """No licence named and no bytes vendored: nothing is invented, and nothing is reported."""
        self.write_registry([{"id": "delta", "license": "NOASSERTION"}])
        (self.vendor / "delta.json").write_text(json.dumps({"id": "delta", "license": "NOASSERTION"}),
                                                encoding="utf-8")
        actions, problems = self.run_import()
        self.assertEqual([], problems)
        self.assertEqual(["no-licence-published"], [action["action"] for action in actions])

    def test_the_gate_is_read_from_the_record_and_the_registry_not_from_an_assumed_field(self) -> None:
        """The registries never carried `license_present`: keying on it made every gap invisible."""
        source = (REPO_ROOT / "tools" / "factory" / "license_manager.py").read_text(encoding="utf-8")
        self.assertNotIn('entry.get("license_present")', source)
        self.assertIn("def wants_a_text", source)
        entries = json.loads((REPO_ROOT / "tools" / "factory" /
                              "imported_factories.json").read_text(encoding="utf-8"))
        sample = entries[0] if isinstance(entries, list) else entries["factories"][0]
        self.assertNotIn("license_present", sample,
                         "the fixture below has to match the registry this tool reads")


class _Never:
    @staticmethod
    def gh_json(_path: str):
        raise AssertionError("this import should not have touched the network")


class _Fetcher(_Never):
    def __init__(self, text: str) -> None:
        self.text = text

    def gh_json(self, path: str):
        if "/contents/" in path:
            return {"content": base64.b64encode(self.text.encode()).decode(), "sha": "abc"}
        if path.endswith("?recursive=1"):
            return {"tree": [{"path": "LICENSE", "type": "blob"}]}
        return {"default_branch": "main"}

    @staticmethod
    def licence_candidates(tree: list[dict]) -> list[str]:
        return [entry["path"] for entry in tree if "LICENSE" in entry["path"]]

    def fetch_text(self, full_name: str, path: str):
        return self.text, "abc"


class _Unreadable(_Never):
    @staticmethod
    def gh_json(_path: str):
        return None


class _NoLicence(_Never):
    @staticmethod
    def gh_json(path: str):
        if path.endswith("?recursive=1"):
            return {"tree": [{"path": "README.md", "type": "blob"}]}
        return {"default_branch": "main"}

    @staticmethod
    def licence_candidates(tree: list[dict]) -> list[str]:
        return []


class ClassifyTest(unittest.TestCase):
    def test_the_classifier_the_index_uses_still_blocks_copyleft(self) -> None:
        """The index records what a text says; if the classifier stopped blocking, it would record that."""
        verdict = license_text.classify("GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007\n"
                                        "Everyone is permitted to copy and distribute verbatim copies "
                                        "of this license document, but changing it is not allowed.")
        self.assertEqual("", verdict["spdx"])
        self.assertTrue(verdict["blocked_by"], "a copyleft text must be blocked, not classified as free")


if __name__ == "__main__":
    unittest.main(verbosity=2)
