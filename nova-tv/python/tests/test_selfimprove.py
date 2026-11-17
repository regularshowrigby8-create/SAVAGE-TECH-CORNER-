"""Tests for the guard rails — the part that must never be permissive."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.selfimprove import (  # noqa: E402
    GuardError,
    Loop,
    Proposal,
    Snapshot,
    is_allowed,
    is_forbidden,
    summarise,
)


class TestGuards(unittest.TestCase):
    def test_forbidden_paths(self):
        for path in [
            "nova/selfimprove.py",
            "nova/config.py",
            ".github/workflows/ci.yml",
            "nova.toml",
            ".env",
            "models.json",
            ".git/config",
        ]:
            self.assertTrue(is_forbidden(path), path)
            self.assertFalse(is_allowed(path), path)

    def test_allowed_paths(self):
        for path in ["nova/catalog.py", "README.md", "python/scripts/x.py", "app/Main.kt"]:
            self.assertTrue(is_allowed(path), path)

    def test_unknown_suffix_rejected(self):
        self.assertFalse(is_allowed("nova/binary.so"))
        self.assertFalse(is_allowed("run.sh"))

    def test_path_traversal_is_forbidden(self):
        self.assertTrue(is_forbidden("./nova/selfimprove.py"))

    def test_proposal_rejects_empty(self):
        with self.assertRaises(GuardError):
            Proposal("nova/x.py", "old", "   ", "reason").validate()

    def test_proposal_rejects_forbidden_target(self):
        with self.assertRaises(GuardError):
            Proposal("nova/selfimprove.py", "a", "b", "reason").validate()

    def test_proposal_rejects_noop(self):
        with self.assertRaises(GuardError):
            Proposal("nova/x.py", "same", "same", "reason").validate()

    def test_proposal_rejects_huge(self):
        with self.assertRaises(GuardError):
            Proposal("nova/x.py", "a", "x" * 2_000_001, "reason").validate()

    def test_proposal_diff_has_headers(self):
        diff = Proposal("nova/x.py", "a = 1\n", "a = 2\n", "r").diff()
        self.assertIn("--- a/nova/x.py", diff)
        self.assertIn("+++ b/nova/x.py", diff)
        self.assertIn("-a = 1", diff)
        self.assertIn("+a = 2", diff)


class TestSnapshot(unittest.TestCase):
    def test_capture_and_restore(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / "nova" / "x.py"
            target.parent.mkdir(parents=True)
            target.write_text("original", "utf-8")
            snap = Snapshot(root=root)
            snap.capture("nova/x.py")
            target.write_text("clobbered", "utf-8")
            restored = snap.restore()
            self.assertEqual(restored, ["nova/x.py"])
            self.assertEqual(target.read_text("utf-8"), "original")

    def test_capture_refuses_forbidden(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(GuardError):
                Snapshot(root=Path(tmp)).capture("nova/selfimprove.py")

    def test_capture_missing_file_is_noop(self):
        with tempfile.TemporaryDirectory() as tmp:
            snap = Snapshot(root=Path(tmp))
            snap.capture("nova/absent.py")
            self.assertEqual(snap.files, {})

    def test_manifest_written(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "a.py").write_text("x", "utf-8")
            snap = Snapshot(root=root)
            snap.capture("a.py")
            path = snap.manifest()
            self.assertTrue(path.exists())
            self.assertIn("a.py", path.read_text("utf-8"))


class TestLoop(unittest.TestCase):
    def test_apply_requires_flag(self):
        with tempfile.TemporaryDirectory() as tmp:
            loop = Loop(root=Path(tmp), allow_apply=False)
            with self.assertRaises(GuardError):
                loop.apply([])

    def test_apply_skips_failing_proposals(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            loop = Loop(root=root, allow_apply=True)
            proposal = loop.propose("x.py", "new content\n", "reason")
            # tests_passed stays False -> must be skipped
            written = loop.apply([proposal])
            self.assertEqual(written, [])
            self.assertFalse((root / "x.py").exists())

    def test_apply_refuses_dirty_tree(self):
        """A dirty tree means the loop would mix its edits with unrelated work."""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "x.py").write_text("old\n", "utf-8")
            loop = Loop(root=root, allow_apply=True)
            proposal = loop.propose("x.py", "new\n", "reason")
            proposal.tests_passed = True
            import nova.selfimprove as si
            original = si.git_is_clean
            si.git_is_clean = lambda _root: False
            try:
                with self.assertRaises(GuardError):
                    loop.apply([proposal])
            finally:
                si.git_is_clean = original
            # the file must be untouched
            self.assertEqual((root / "x.py").read_text("utf-8"), "old\n")

    def test_apply_writes_passing_proposal_on_clean_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "x.py").write_text("old\n", "utf-8")
            loop = Loop(root=root, allow_apply=True)
            proposal = loop.propose("x.py", "new\n", "reason")
            proposal.tests_passed = True
            import nova.selfimprove as si
            original = si.git_is_clean
            si.git_is_clean = lambda _root: True
            try:
                written = loop.apply([proposal])
            finally:
                si.git_is_clean = original
            self.assertEqual(written, ["x.py"])
            self.assertEqual((root / "x.py").read_text("utf-8"), "new\n")
            self.assertTrue((root / ".nova-snapshots").exists())

    def test_apply_never_writes_forbidden_even_when_flagged_passing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            loop = Loop(root=root, allow_apply=True)
            with self.assertRaises(GuardError):
                loop.propose(".github/workflows/ci.yml", "on: push\n", "malicious")

    def test_rollback_restores(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / "x.py"
            target.write_text("before", "utf-8")
            loop = Loop(root=root, allow_apply=True)
            loop.propose("x.py", "after", "reason")
            target.write_text("mangled", "utf-8")
            loop.rollback()
            self.assertEqual(target.read_text("utf-8"), "before")

    def test_report_shape(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = Loop(root=Path(tmp)).report()
            for key in ("head", "clean", "apply_allowed", "snapshot", "log"):
                self.assertIn(key, report)


class TestSummarise(unittest.TestCase):
    def test_counts(self):
        good = Proposal("a.py", "x", "y", "r")
        good.tests_passed = True
        bad = Proposal("b.py", "x", "z", "r")
        self.assertEqual(summarise([good, bad])["valid"], ["a.py"])
        self.assertEqual(summarise([good, bad])["blocked"], ["b.py"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
