import unittest
from pathlib import Path
import sys

_curr = Path(__file__).resolve()
TOOLS_DIR = _curr.parent.parent / "tools"
sys.path.insert(0, str(TOOLS_DIR))

from browser_app_rules import (
    ANDROID_PROJECT_DIR,
    verify_browser_app_rules,
    verify_browser_source_rules,
)


class TestBrowserAppRules(unittest.TestCase):
    def test_source_architecture_passes_rules(self):
        """The retained Android source architecture must satisfy the provenance,
        completeness, and anti-mock browser rules (no APK binary is tracked)."""
        res = verify_browser_source_rules(ANDROID_PROJECT_DIR)
        self.assertTrue(res["passed"], f"Violations: {res.get('violations')}")

    def test_apk_binary_audit_is_deprecated_fail_closed(self):
        """The old APK-binary audit entry point must refuse clearly (fail-closed),
        never silently pass, since no APK is tracked in the repository."""
        with self.assertRaises(NotImplementedError):
            verify_browser_app_rules("anything.apk")


if __name__ == "__main__":
    unittest.main()
