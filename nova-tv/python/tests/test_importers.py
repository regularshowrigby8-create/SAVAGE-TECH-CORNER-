"""The import must read the real constants, not guess at them."""
from __future__ import annotations

import unittest
from pathlib import Path

from nova.importers import (
    extract_hold_reasons,
    extract_skip_seconds,
    extract_timings,
    freeze_block,
    render_kotlin,
    source_root,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
PLAYER_CORE = REPO_ROOT / "tv" / "zeroplay-debrid" / "lib" / "player-core.js"


class SourceRootTest(unittest.TestCase):
    def test_source_root_is_above_nova_tv(self):
        # nova-tv is the output root; sources live in the repository above it.
        self.assertEqual(source_root(Path("/repo/nova-tv")), Path("/repo"))


class FreezeBlockTest(unittest.TestCase):
    def test_reads_only_the_named_block(self):
        js = 'const A = Object.freeze({ X: 1 });\nconst B = Object.freeze({ Y: 2 });'
        self.assertIn("X: 1", freeze_block(js, "A"))
        self.assertNotIn("Y", freeze_block(js, "A"))

    def test_missing_block_is_empty(self):
        self.assertEqual(freeze_block("const A = 1;", "A"), "")


class ExtractionTest(unittest.TestCase):
    def test_prose_numbers_are_not_constants(self):
        # The bug this pins: a bare `\w+: number` scan reads "5% per press" out
        # of a comment and reports it as a timing.
        js = '/* Volume steps: 5% per press. */\nconst TIMING = Object.freeze({ IDLE_MS: 2000 });'
        self.assertEqual(extract_timings(js), {"IDLE_MS": 2000})

    def test_block_scoped_scan(self):
        js = 'const TIMING = Object.freeze({\n  IDLE_MS: 2000,\n  TAP_MS: 250\n});'
        self.assertEqual(extract_timings(js), {"IDLE_MS": 2000, "TAP_MS": 250})

    def test_multiline_comment_inside_block_does_not_break_scan(self):
        js = 'const TIMING = Object.freeze({\n  /* two\n     lines 9 */\n  IDLE_MS: 2000\n});'
        self.assertEqual(extract_timings(js), {"IDLE_MS": 2000})

    def test_real_player_core_constants_are_evidence_only(self):
        # A skipped test is a silent success. When the optional upstream file
        # is absent, assert that fact and that no checked-in doc claims the
        # missing file exists. When it is present, assert its real constants.
        if not PLAYER_CORE.is_file():
            docs = sorted((REPO_ROOT / "nova-tv" / "docs").glob("*.md"))
            claimants = [str(doc) for doc in docs if "tv/zeroplay-debrid/lib/player-core.js" in doc.read_text(errors="ignore")]
            self.assertEqual([], claimants, "missing upstream source is still claimed by a doc")
            self.assertFalse(PLAYER_CORE.is_file(), "optional upstream source is absent and explicitly accounted for")
            return
        js = PLAYER_CORE.read_text()
        timings = extract_timings(js)
        self.assertEqual(len(timings), 8)
        self.assertEqual(timings["IDLE_MS"], 2000)
        self.assertEqual(timings["IDLE_TOUCH_MS"], 3000)
        self.assertEqual(timings["TAP_THRESHOLD_MS"], 250)
        self.assertEqual(timings["TOUCH_SETTLE_MS"], 500)
        self.assertEqual(timings["CAPTURE_RELEASE_MS"], 100)
        self.assertEqual(timings["DOUBLE_TAP_MS"], 280)
        self.assertEqual(timings["LONG_PRESS_MS"], 1500)
        self.assertEqual(timings["SEEK_SETTLE_MS"], 400)
        self.assertNotIn("steps", timings)

        self.assertEqual(extract_skip_seconds(js), {"tap": 10, "key": 10, "hold": 30})
        self.assertEqual(
            extract_hold_reasons(js),
            ["lock", "paused", "interacting", "menu", "error", "ended", "casting"],
        )


class RenderTest(unittest.TestCase):
    def test_generated_kotlin_is_valid_shape(self):
        kotlin = render_kotlin(
            {"IDLE_MS": 2000},
            {"tap": 10},
            ["lock", "paused"],
            "test/source.js",
        )
        self.assertTrue(kotlin.startswith("package dev.nova.tv.player"))
        self.assertIn("const val IDLE_MS = 2000L", kotlin)
        self.assertIn("const val TAP = 10000L", kotlin)  # seconds -> milliseconds
        self.assertIn("LOCK,", kotlin)
        self.assertIn("PAUSED", kotlin)

    def test_no_duplicate_braces(self):
        kotlin = render_kotlin({"IDLE_MS": 2000}, {"tap": 10}, ["lock"], "src")
        self.assertEqual(kotlin.count("{"), kotlin.count("}"))


if __name__ == "__main__":
    unittest.main()
