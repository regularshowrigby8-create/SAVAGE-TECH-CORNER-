#!/usr/bin/env python3
"""Tests for debug_pipeline.py — added based on Factory 2 quality agent feedback."""

import unittest
import sys
sys.path.insert(0, '.')
from workspace.code.debug_pipeline import run_debug_pipeline, scan_for_dead_code, scan_for_dead_buttons

class TestDebugPipeline(unittest.TestCase):
    def test_main_pipeline_runs(self):
        result = run_debug_pipeline()
        self.assertTrue(result)

    def test_scan_for_dead_code(self):
        results = scan_for_dead_code()
        self.assertIsInstance(results, list)

    def test_scan_for_dead_buttons(self):
        results = scan_for_dead_buttons()
        self.assertIsInstance(results, list)

if __name__ == "__main__":
    unittest.main()
