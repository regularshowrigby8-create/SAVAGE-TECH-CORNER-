"""Traceability check: every acceptance criterion names a test that exists.

`docs/ACCEPTANCE_CRITERIA.md` is the contract for the Android deliverable. A criterion whose
`Test method` points at a test that does not exist is a claim without evidence, so this suite reads
the document and resolves every backticked `Class#method` reference against the repository:

* a Java reference must resolve to a real `.java` file whose source declares that method;
* a path reference (`tools/foo.py`, `shell/tests/bar.py`) must resolve to a real file;
* a command reference (`./gradlew …`, `aapt2 …`, `adb …`) must be a command, and is reported so it
  is visible in the test output rather than silently ignored.

The suite fails when a criterion references a missing test, and it also fails when the document
loses a criterion that the repository used to satisfy.
"""

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CRITERIA = REPO_ROOT / "docs" / "ACCEPTANCE_CRITERIA.md"

MANIFEST = "shell/android_project/app/src/main/AndroidManifest.xml"

SEARCH_ROOTS = (
    "shell/android_project/app/src/test/java",
    "shell/android_project/app/src/androidTest/java",
    "shell/android_project/app/src/main/java",
    "shell/tests",
    "tools",
    "factory-2",
    "factory-3",
    "factories",
    "scripts",
)


def load_criteria() -> str:
    return CRITERIA.read_text(encoding="utf-8")


def find_java_test(class_name: str) -> Path:
    for root in SEARCH_ROOTS:
        for path in (REPO_ROOT / root).rglob(f"{class_name}.java"):
            return path
    raise AssertionError(f"no Java source declares {class_name}")


class AcceptanceTraceabilityTest(unittest.TestCase):
    def setUp(self):
        self.text = load_criteria()
        self.criteria = re.findall(r"^### (AC-\d+)", self.text, re.M)
        self.test_method_lines = re.findall(r"^- \*\*Test method\*\*: (.+)$", self.text, re.M)

    def test_the_document_lists_the_expected_criteria(self):
        self.assertGreaterEqual(len(self.criteria), 40,
                                "the acceptance criteria document lost criteria")
        self.assertEqual(len(self.criteria), len(self.test_method_lines),
                         "every criterion needs exactly one Test method line")
        self.assertEqual(len(self.criteria), len(set(self.criteria)), "duplicate AC ids")

    def test_every_java_test_reference_exists(self):
        references = re.findall(r"`([A-Z][A-Za-z0-9_]*(?:Test|Activity))#([A-Za-z0-9_]+)`", self.text)
        self.assertTrue(references, "no Java test references found in the criteria document")
        missing = []
        checked = set()
        for class_name, method in references:
            if (class_name, method) in checked:
                continue
            checked.add((class_name, method))
            try:
                source = find_java_test(class_name)
            except AssertionError as problem:
                missing.append(str(problem))
                continue
            body = source.read_text(encoding="utf-8")
            declaration = re.search(
                rf"public\s+void\s+{re.escape(method)}\s*\(\s*\)", body)
            if declaration is None:
                missing.append(f"{class_name}#{method} is not declared in "
                               f"{source.relative_to(REPO_ROOT)}")
        self.assertEqual([], missing)

    def test_every_file_reference_exists(self):
        references = re.findall(r"`((?:tools|shell|factory-2|factory-3|factories|scripts)/[\w./-]+)`",
                                self.text)
        missing = []
        for reference in sorted(set(references)):
            if not (REPO_ROOT / reference).exists():
                missing.append(reference)
        self.assertEqual([], missing)

    def test_every_gradle_or_device_command_is_a_real_command(self):
        """Commands named by the criteria are checked for being commands, not placeholders."""
        commands = re.findall(r"`((?:\./gradlew|adb|aapt2|apksigner)\s[^`]+)`", self.text)
        self.assertTrue(commands, "the criteria document should name the real build commands")
        for command in commands:
            with self.subTest(command=command):
                self.assertFalse(command.strip().endswith("..."), "no elided commands")
                self.assertNotIn("TODO", command)

    def test_criteria_cover_the_required_categories(self):
        """The brief's mandatory categories must stay covered by id."""
        required = {
            "clean first launch": "AC-001",
            "navigation": "AC-002",
            "journey J1": "AC-004",
            "journey J2": "AC-005",
            "journey J3": "AC-006",
            "journey J4": "AC-007",
            "journey J5": "AC-008",
            "journey J6": "AC-009",
            "invalid input": "AC-013",
            "empty state": "AC-016",
            "loading state": "AC-019",
            "error state": "AC-020",
            "offline": "AC-009",
            "persistence after restart": "AC-023",
            "rotation": "AC-026",
            "permission denial": "AC-028",
            "back navigation": "AC-030",
            "accessibility": "AC-032",
            "duplicate actions": "AC-034",
            "process restart": "AC-037",
            "corrupted local data": "AC-038",
            "release install + launch": "AC-044",
        }
        for category, criterion in sorted(required.items()):
            with self.subTest(category=category):
                self.assertIn(f"### {criterion} ", self.text,
                              f"{category} must stay covered by {criterion}")

    def test_manifest_referenced_by_the_criteria_exists(self):
        self.assertTrue((REPO_ROOT / MANIFEST).is_file())


if __name__ == "__main__":
    unittest.main()
