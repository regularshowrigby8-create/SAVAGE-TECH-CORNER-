"""Tests for Factory 3 Android refinement tools."""

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "tools"
sys.path.insert(0, str(ROOT))

from android_tools import AndroidManifestAuditor, AndroidResourceRefiner, Factory3Refiner


class TestAndroidTools(unittest.TestCase):
    def setUp(self) -> None:
        self.refiner = Factory3Refiner()

    def test_manifest_auditor_clean(self) -> None:
        manifest = {
            "package": "com.savage.soul",
            "minSdkVersion": 28,
            "targetSdkVersion": 35,
            "permissions": ["android.permission.INTERNET"],
            "activities": [{"name": ".Main", "exported": True, "intent_filters": [{"action": "android.intent.action.MAIN"}]}],
        }
        res = self.refiner.manifest_auditor.audit_manifest(manifest)
        self.assertTrue(res["passed"])
        self.assertEqual(len(res["findings"]), 0)

    def test_refiner_certifies_code(self) -> None:
        result = self.refiner.process_and_certify(".")
        self.assertEqual(result["status"], "CERTIFIED")
        self.assertTrue(result["certified_for_final_product"])


if __name__ == "__main__":
    unittest.main()
