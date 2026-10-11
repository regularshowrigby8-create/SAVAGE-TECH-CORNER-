"""Unit tests for Modern Lighting Soul Browser Shell."""

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from soul_browser.browser_shell import SoulBrowserShell, WebViewHolder, WebViewBridge, ModernLightingUITheme


class TestSoulBrowserShell(unittest.TestCase):
    def setUp(self) -> None:
        self.shell = SoulBrowserShell()

    def test_webview_initialization(self) -> None:
        self.assertEqual(self.shell.webview.current_url, "about:blank")
        self.assertTrue(self.shell.webview.dom_storage_enabled)
        self.assertTrue(self.shell.webview.javascript_enabled)

    def test_navigation_and_history(self) -> None:
        self.shell.webview.load_url("https://example.com")
        self.assertEqual(self.shell.webview.current_url, "https://example.com")
        self.shell.webview.load_url("https://example.org")
        self.assertTrue(self.shell.webview.can_go_back())
        back_url = self.shell.webview.go_back()
        self.assertEqual(back_url, "https://example.com")
        self.assertTrue(self.shell.webview.can_go_forward())
        forward_url = self.shell.webview.go_forward()
        self.assertEqual(forward_url, "https://example.org")

    def test_bridge_invocation(self) -> None:
        res = self.shell.bridge.invoke("getAppVersion")
        self.assertEqual(res, "1.0.0-soul")

    def test_all_ui_buttons_handled(self) -> None:
        required_buttons = ["btn_back", "btn_forward", "btn_refresh", "btn_home", "btn_menu", "btn_tabs"]
        for btn in required_buttons:
            out = self.shell.click_button(btn)
            self.assertIsInstance(out, dict)

    def test_dead_button_fails_closed(self) -> None:
        with self.assertRaises(RuntimeError):
            self.shell.click_button("btn_nonexistent")

    def test_modern_lighting_ui_state_rendering(self) -> None:
        state = self.shell.render_modern_ui_state()
        self.assertIn("theme", state)
        self.assertIn("omnibox", state)
        self.assertIn("controls", state)
        self.assertEqual(state["omnibox"]["display_url"], "about:blank")
        self.assertFalse(state["controls"]["back_enabled"])
        
        # Load secure HTTPS URL and re-verify state
        self.shell.webview.load_url("https://savage-tech-corner.io")
        updated_state = self.shell.render_modern_ui_state()
        self.assertTrue(updated_state["omnibox"]["is_secure"])
        self.assertEqual(updated_state["omnibox"]["title"], "savage-tech-corner.io")


if __name__ == "__main__":
    unittest.main()
