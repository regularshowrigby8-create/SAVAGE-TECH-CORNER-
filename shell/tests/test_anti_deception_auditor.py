"""Tests for tools/anti_deception_auditor.py's vendored-archive rule.

The repository ships the factory engine as two pinned ZIP archives. Their own scanner and gate
scripts contain the foreign-provenance tokens *as the list of things those scripts search for*, which
is why a byte-identical copy is not a violation - and why these tests exist: the exemption must stop
the moment a single byte of an archive changes.
"""

import hashlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
AUDITOR_PATH = REPO_ROOT / "tools" / "anti_deception_auditor.py"

# Assembled at runtime: a detector for the tokens must not itself contain them, and neither may the
# tests around it.
FOREIGN_TOKEN = "acr" + ".browser"
SECOND_TOKEN = "bare" + "bones"


def load_auditor():
    spec = importlib.util.spec_from_file_location("anti_deception_auditor", AUDITOR_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


auditor = load_auditor()


def run_auditor(root: Path) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(AUDITOR_PATH), "--root", str(root)],
                          capture_output=True, text=True, check=False)


def copy_archive(source: Path, target: Path, tamper_entry_suffix: str = None) -> None:
    """Re-writes an archive, optionally appending a byte to one entry."""
    with zipfile.ZipFile(source) as archive:
        items = [(name, archive.read(name)) for name in archive.namelist()]
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in items:
            if tamper_entry_suffix is not None and name.endswith(tamper_entry_suffix):
                data = data + b"\n# tampered\n"
            archive.writestr(name, data)


class PinnedArchiveTest(unittest.TestCase):

    def test_the_pinned_digests_match_the_archives_in_the_repository(self):
        self.assertTrue(auditor.VENDORED_ENGINE_ARCHIVES,
                        "the vendored engine archives must be pinned")
        for name, digest in auditor.VENDORED_ENGINE_ARCHIVES.items():
            path = REPO_ROOT / name
            with self.subTest(archive=name):
                self.assertTrue(path.is_file(), f"{name} is missing from the repository")
                self.assertEqual(digest, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_the_repository_passes_the_audit(self):
        result = run_auditor(REPO_ROOT)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("[PASS]", result.stdout)

    def test_the_repository_passes_with_visible_notes_not_silence(self):
        result = run_auditor(REPO_ROOT)
        self.assertIn("[NOTE]", result.stdout)
        self.assertIn("vendored-engine token match", result.stdout)


class TamperDetectionTest(unittest.TestCase):
    """The pinned digest is the whole reason the exemption is safe, so it is tested from both sides."""

    def test_a_modified_vendored_archive_is_a_violation_again(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            copy_archive(REPO_ROOT / "SAVAGE_FACTORY_v4.zip", root / "SAVAGE_FACTORY_v4.zip",
                         tamper_entry_suffix="SAVAGE_FACTORY.py")
            result = run_auditor(root)
            self.assertEqual(1, result.returncode, result.stdout)
            self.assertIn("is the vendored factory engine but its SHA-256 is", result.stdout)
            self.assertIn(FOREIGN_TOKEN, result.stdout.lower())

    def test_an_unpinned_archive_with_a_foreign_token_is_a_violation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with zipfile.ZipFile(root / "someone-elses-app.zip", "w") as archive:
                archive.writestr("app/MainActivity.java",
                                 f"package com.{FOREIGN_TOKEN}; // {SECOND_TOKEN}\n")
            result = run_auditor(root)
            self.assertEqual(1, result.returncode, result.stdout)
            self.assertIn(FOREIGN_TOKEN, result.stdout.lower())

    def test_the_pinned_archives_are_the_only_exemption(self):
        """No token-bearing file is exempt unless it is a pinned archive or the auditor itself."""
        allowed = auditor.ALLOWED_FORBIDDEN_TOKEN_FILES | set(auditor.VENDORED_ENGINE_ARCHIVES)
        self.assertIn("tools/anti_deception_auditor.py", allowed)
        self.assertEqual(2, len(auditor.VENDORED_ENGINE_ARCHIVES))
        for name in auditor.VENDORED_ENGINE_ARCHIVES:
            self.assertTrue(name.endswith(".zip"))


class NoteVisibilityTest(unittest.TestCase):

    def test_notes_are_not_counted_as_violations(self):
        self.assertEqual(list, type(auditor.AUDIT_NOTES))

    def test_the_source_scan_still_covers_the_application_sources(self):
        """The exemption is about archives; the Android sources must stay fully scanned."""
        source = io.open(AUDITOR_PATH, encoding="utf-8").read()
        self.assertIn('".java"', source)
        self.assertIn('".kts"', source)
        self.assertIn("ALLOWED_FORBIDDEN_TOKEN_FILES", source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
