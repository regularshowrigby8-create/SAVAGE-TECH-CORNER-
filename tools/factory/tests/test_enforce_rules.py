#!/usr/bin/env python3
"""
Unit tests for tools/factory/enforce_rules.py
"""

import unittest
import json
import tempfile
from pathlib import Path
from tools.factory.enforce_rules import verify_and_test_bypass, is_rule_bypassed, MINIMUM_REASON_LENGTH

class TestEnforceRulesGovernance(unittest.TestCase):

    def test_bypass_rejection_on_short_or_missing_reason(self):
        req = {
            "rule_id": "ANTI_STUB_RULE",
            "justification": "I just need to commit quick.",
            "solution_files": ["shell/browser_shell.py"]
        }
        res = verify_and_test_bypass(req)
        self.assertFalse(res["approved"])
        self.assertIn("Bypass justification rejected", res["error"])

    def test_bypass_rejection_on_missing_solution(self):
        long_reason = (
            "We have encountered an unprecedented hardware constraint with architecture compatibility "
            "that necessitates an immediate exception while upstream drivers stabilize over the next week."
        )
        req = {
            "rule_id": "ANTI_STUB_RULE",
            "justification": long_reason,
            "solution_files": []
        }
        res = verify_and_test_bypass(req)
        self.assertFalse(res["approved"])
        self.assertIn("No solution files provided", res["error"])

    def test_bypass_rejection_on_nonexistent_solution_file(self):
        long_reason = (
            "We have encountered an unprecedented hardware constraint with architecture compatibility "
            "that necessitates an immediate exception while upstream drivers stabilize over the next week."
        )
        req = {
            "rule_id": "ANTI_STUB_RULE",
            "justification": long_reason,
            "solution_files": ["non_existent_file_path_xyz.py"]
        }
        res = verify_and_test_bypass(req)
        self.assertFalse(res["approved"])
        self.assertIn("Solution file not found on disk", res["error"])

    def test_default_state_is_not_bypassed(self):
        self.assertFalse(is_rule_bypassed("RANDOM_RULE_XYZ"))

if __name__ == "__main__":
    unittest.main()
