#!/usr/bin/env python3
"""Offline tests that applying the Nova patch cannot overwrite unrelated files."""
import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import apply_nova_patch as patch


class ApplyPatchTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.root = base / "patch"
        self.target = base / "checkout"
        (self.root / "nova-tv").mkdir(parents=True)
        (self.target / "nova-tv").mkdir(parents=True)
        (self.root / "README.md").write_text("patch README")
        (self.target / "README.md").write_text("original README")
        (self.root / "nova-tv" / "old.kt").write_text("patched")
        (self.root / "nova-tv" / "new.kt").write_text("new")
        (self.target / "nova-tv" / "old.kt").write_text("original")
        original_hash = hashlib.sha256(b"original").hexdigest()
        (self.root / "BASELINE.json").write_text(json.dumps({
            "base_commit": "fixture",
            "files": {"nova-tv/old.kt": original_hash, "nova-tv/new.kt": None},
        }))
        previous = patch.PATCH_ROOT
        patch.PATCH_ROOT = self.root
        self.addCleanup(setattr, patch, "PATCH_ROOT", previous)

    def run_tool(self, option):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return patch.main(["--target", str(self.target), option])

    def test_check_is_read_only(self):
        self.assertEqual(0, self.run_tool("--check"))
        self.assertFalse((self.target / "nova-tv" / "new.kt").exists())
        self.assertFalse((self.target / patch.BACKUP_DIR).exists())
        self.assertEqual("original", (self.target / "nova-tv" / "old.kt").read_text())

    def test_write_preserves_root_readme_and_creates_backup(self):
        self.assertEqual(0, self.run_tool("--write"))
        self.assertEqual("original README", (self.target / "README.md").read_text())
        self.assertEqual("patched", (self.target / "nova-tv" / "old.kt").read_text())
        self.assertEqual("new", (self.target / "nova-tv" / "new.kt").read_text())
        self.assertEqual("original", (self.target / patch.BACKUP_DIR / "nova-tv" / "old.kt").read_text())
        self.assertEqual(0, self.run_tool("--write"))  # idempotent

    def test_one_drifted_file_prevents_all_writes(self):
        (self.target / "nova-tv" / "old.kt").write_text("local work")
        self.assertEqual(1, self.run_tool("--write"))
        self.assertEqual("local work", (self.target / "nova-tv" / "old.kt").read_text())
        self.assertFalse((self.target / "nova-tv" / "new.kt").exists())

    def test_unrecorded_patch_path_is_a_conflict(self):
        (self.root / "nova-tv" / "unrecorded.kt").write_text("unreviewed")
        self.assertEqual(1, self.run_tool("--check"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
