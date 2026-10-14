"""Checks on the imported-factory registry and its catalog.

The registry (`tools/factory/imported_factories.json`) is what the multistage factory composes its
stages from, so it is held to the same standard as the rest of this repository's data: every entry
carries the licence the repository actually publishes, the evidence that makes it comparable to this
factory, and an import class that cannot be upgraded by editing a string. The catalog that humans
read must list exactly the same factories the machine reads - a catalog that drifts from the registry
would be documentation, not a source of truth.
"""

import json
import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REGISTRY = REPO_ROOT / "tools" / "factory" / "imported_factories.json"
CATALOG = REPO_ROOT / "docs" / "factory" / "IMPORTED_FACTORY_CATALOG.md"

#: Licences that permit reuse. An entry outside this set may never be classed as importable.
PERMISSIVE_LICENCES = {
    "MIT",
    "Apache-2.0",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "ISC",
    "CC0-1.0",
    "Unlicense",
    "0BSD",
}

#: The characteristics that make a repository a *factory* for this project, not just an agent demo.
CHARACTERISTICS = {
    "worker/agent code",
    "stages",
    "orchestration",
    "factory naming",
    "verification/review",
    "build/test/ship steps",
}

REQUIRED_FIELDS = ("id", "full_name", "url", "license", "stars", "evidence", "evidence_count",
                   "ai_worker_evidence", "import_class", "snapshot")

#: Tokens the repository's authenticity auditor treats as foreign identifiers. A registry that
#: mentions one of them would fail that gate, so the registry must be clean by construction.
FORBIDDEN_TOKENS = ("barebones", "lightning", "acr.browser", "anthonycr", "client=lightning")


def load_registry() -> list:
    if not REGISTRY.is_file():
        raise AssertionError(f"the imported-factory registry is missing: {REGISTRY}")
    return json.loads(REGISTRY.read_text(encoding="utf-8"))


class ImportedFactoryRegistryTest(unittest.TestCase):

    def setUp(self):
        self.entries = load_registry()

    def test_the_catalog_holds_at_least_fifty_factories(self):
        self.assertGreaterEqual(len(self.entries), 50,
                                "the registry must hold at least 50 verified similar factories")

    def test_every_entry_is_complete_and_has_a_stable_id(self):
        seen = set()
        for entry in self.entries:
            for field in REQUIRED_FIELDS:
                with self.subTest(entry=entry.get("id"), field=field):
                    self.assertIn(field, entry)
                    self.assertNotIn(entry[field], (None, "", [], {}))
            self.assertNotIn(entry["id"], seen, f"duplicate id {entry['id']}")
            seen.add(entry["id"])

    def test_every_entry_points_at_a_real_repository_url(self):
        for entry in self.entries:
            with self.subTest(entry=entry["id"]):
                self.assertEqual(f'https://github.com/{entry["full_name"]}', entry["url"])
                self.assertRegex(entry["full_name"], r"^[\w.-]+/[\w.-]+$")

    def test_every_entry_shows_at_least_four_factory_characteristics(self):
        for entry in self.entries:
            with self.subTest(entry=entry["id"]):
                evidence = set(entry["evidence"])
                self.assertGreaterEqual(entry["evidence_count"], 4)
                self.assertEqual(entry["evidence_count"], len(entry["evidence"]),
                                 "evidence_count must count the evidence it lists")
                self.assertTrue(evidence.issubset(CHARACTERISTICS),
                                f"unknown characteristic: {evidence - CHARACTERISTICS}")

    def test_only_permissively_licensed_repositories_are_importable(self):
        for entry in self.entries:
            with self.subTest(entry=entry["id"]):
                self.assertIn(entry["import_class"], ("asset-import", "blueprint-only"))
                if entry["import_class"] == "asset-import":
                    self.assertIn(entry["license"], PERMISSIVE_LICENCES,
                                  f'{entry["full_name"]} carries {entry["license"]} and must not be '
                                  f"classed as importable")

    def test_the_catalog_stays_focused_on_ai_workers(self):
        workers = [entry for entry in self.entries if entry["ai_worker_evidence"]]
        self.assertGreaterEqual(
            len(workers) * 2, len(self.entries),
            "the majority of imported factories must be the ones that run AI workers")

    def test_the_registry_records_when_it_was_taken(self):
        for entry in self.entries:
            with self.subTest(entry=entry["id"]):
                self.assertRegex(entry["snapshot"], r"^\d{4}-\d{2}-\d{2}$",
                                 "stars and licences change, so every entry is a dated observation")
                self.assertIn("T", entry["pushed_at"])

    def test_the_registry_carries_no_foreign_identifier(self):
        blob = json.dumps(self.entries).lower()
        for token in FORBIDDEN_TOKENS:
            with self.subTest(token=token):
                self.assertNotIn(token, blob,
                                 "the repository's authenticity auditor rejects these tokens")

    def test_the_catalog_document_lists_exactly_the_registry(self):
        catalog = CATALOG.read_text(encoding="utf-8")
        for entry in self.entries:
            with self.subTest(entry=entry["id"]):
                self.assertIn(f'`{entry["id"]}`', catalog,
                              f'{entry["id"]} is in the registry but not in the catalog')
                self.assertIn(entry["url"], catalog)
        # Only the catalog table: its rows are `| `<id>` | [repo](url) | ...`, which distinguishes
        # them from the class table above it.
        listed = set(re.findall(r"^\| `([\w.-]+)` \| \[", catalog, re.M))
        self.assertEqual({entry["id"] for entry in self.entries}, listed,
                         "the catalog lists an id the registry does not hold")

    def test_the_catalog_states_the_licence_policy(self):
        catalog = CATALOG.read_text(encoding="utf-8")
        self.assertIn("asset-import", catalog)
        self.assertIn("blueprint-only", catalog)
        self.assertIn("No licence", catalog,
                      "the catalog must say that unlicensed repositories are never copied from")


if __name__ == "__main__":
    unittest.main()
