#!/usr/bin/env python3
"""Behavioral tests for Factory 1 scanners."""

import tempfile
import unittest
from pathlib import Path

from workspace.code.debug_pipeline import (
    run_debug_pipeline,
    scan_for_dead_buttons,
    scan_for_dead_code,
    scan_for_incomplete,
)


class TestDebugPipeline(unittest.TestCase):
    def test_clean_fixture_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "clean.py").write_text("def used():\n    return 1\nprint(used())\n", encoding="utf-8")
            self.assertEqual(run_debug_pipeline(root), {"dead_code": [], "incomplete": [], "dead_buttons": [], "passed": True})

    def test_unreferenced_definition_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "sample.py"
            path.write_text("def orphan():\n    return 1\n", encoding="utf-8")
            findings = scan_for_dead_code(tmp)
            self.assertTrue(any("orphan" in finding for finding in findings))

    def test_incomplete_marker_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "sample.py").write_text("# TODO: INCOMPLETE\n", encoding="utf-8")
            self.assertEqual(len(scan_for_incomplete(tmp)), 1)

    def test_button_without_handler_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "page.html").write_text("<button>Broken</button>", encoding="utf-8")
            self.assertEqual(len(scan_for_dead_buttons(tmp)), 1)

    def test_button_with_handler_is_clean(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "page.html").write_text('<button onclick="submitForm()">Send</button>', encoding="utf-8")
            self.assertEqual(scan_for_dead_buttons(tmp), [])


if __name__ == "__main__":
    unittest.main()
