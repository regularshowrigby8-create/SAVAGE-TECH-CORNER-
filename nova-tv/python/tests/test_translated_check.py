"""The translated-finder checker must itself be honest.

A checker that always says ok is a false green. These tests prove the
checker catches the failures it claims to catch, and that the current
tree passes it.
"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from nova import translated_check


class TranslatedCheckTest(unittest.TestCase):
    def test_current_tree_passes(self):
        root = Path(__file__).resolve().parent.parent.parent
        checks = translated_check.run_all(root)
        summary = translated_check.summarise(checks)
        self.assertEqual([], summary["contradicted"], summary["contradicted"])
        self.assertTrue(summary["ok"], summary)

    def test_missing_file_is_contradicted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            checks = translated_check.check_files_exist(root)
            self.assertTrue(any(c.verdict == "contradicted" for c in checks))
            self.assertFalse(translated_check.summarise(checks)["ok"])

    def test_stub_is_contradicted(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            base = root / "android/app/src/main/java/dev/nova/tv/finder/translated"
            base.mkdir(parents=True)
            (base / "FinderHelpers.kt").write_text("package dev.nova.tv.finder.translated\n// TODO fix\n")
            checks = translated_check.check_no_stubs(root)
            self.assertTrue(any(c.verdict == "contradicted" for c in checks))

    def test_score_formulas_are_monotonic(self):
        self.assertGreater(
            translated_check.score_of_server(100_000, 400, 90, 100),
            translated_check.score_of_server(100, 100, 90, 100),
        )
        self.assertGreater(
            translated_check.score_of_addon(100_000, 400, 60, 90, 100),
            translated_check.score_of_addon(100, 100, 2, 90, 100),
        )

    def test_buffer_score_clamps(self):
        self.assertEqual(100, translated_check.buffer_score(0, 0, 0, 10_000))
        self.assertEqual(0, translated_check.buffer_score(10000, 99, 9999, 0))

    def test_quality_tables_match_python(self):
        self.assertEqual(("2160p", 400), translated_check.quality_of("watch 4k uhd"))
        self.assertEqual(("720p", 200), translated_check.quality_of("720", translated_check.ADDON_QUALITY))
        self.assertEqual(("unknown", 0), translated_check.quality_of("something else"))

    def test_discovery_is_seedless_and_manifest_paths_are_complete(self):
        self.assertFalse(hasattr(translated_check, "SERVER_SEEDS"))
        self.assertFalse(hasattr(translated_check, "ADDON_SEEDS"))
        self.assertEqual(6, len(translated_check.MANIFEST_PATHS))
        self.assertEqual(3, len(translated_check.PROBE_IDS))
        self.assertEqual(10_000, translated_check.SERVER_CAP)
        self.assertEqual(10_000, translated_check.ADDON_CAP)

    def test_summarise_never_false_green(self):
        bad = [translated_check.Check("x", "contradicted", "nope")]
        self.assertFalse(translated_check.summarise(bad)["ok"])
        good = [translated_check.Check("x", "proven", "yes")]
        self.assertTrue(translated_check.summarise(good)["ok"])


if __name__ == "__main__":
    unittest.main()
