import unittest
from pathlib import Path
import sys

_curr = Path(__file__).resolve()
TOOLS_DIR = _curr.parent.parent / "tools"
sys.path.insert(0, str(TOOLS_DIR))

from browser_app_rules import verify_browser_app_rules


class TestBrowserAppRules(unittest.TestCase):
    def test_current_apk_passes_rules(self):
        root = Path(__file__).resolve().parents[2]
        apk_path = root / "factories" / "final-product" / "SavageBrowser-debug.apk"
        res = verify_browser_app_rules(apk_path)
        self.assertTrue(res["passed"], f"Violations: {res.get('violations')}")
        self.assertGreaterEqual(res["artifact_size_bytes"], 50000)


if __name__ == "__main__":
    unittest.main()
