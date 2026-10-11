import unittest
from pathlib import Path
import sys

_curr = Path(__file__).resolve()
TOOLS_DIR = _curr.parent.parent / "tools"
sys.path.insert(0, str(TOOLS_DIR))

from browser_app_rules import verify_browser_app_rules


class TestBrowserAppRules(unittest.TestCase):
    def test_current_apk_passes_rules(self):
        # Locate project root whether running from factories/factory-3 or root mirror
        curr = Path(__file__).resolve()
        # Find root containing 'factories' or 'shell'
        root = curr.parents[2]
        if not (root / "factories").exists() and curr.parents[3].exists():
            root = curr.parents[3]

        apk_path = root / "factories" / "final-product" / "SavageBrowser-debug.apk"
        if not apk_path.exists():
            apk_path = root / "shell" / "final-product" / "SavageBrowser-debug.apk"
        
        self.assertTrue(apk_path.exists(), f"APK path does not exist: {apk_path}")
        res = verify_browser_app_rules(apk_path)
        self.assertTrue(res["passed"], f"Violations: {res.get('violations')}")
        self.assertGreaterEqual(res["artifact_size_bytes"], 50000)


if __name__ == "__main__":
    unittest.main()
