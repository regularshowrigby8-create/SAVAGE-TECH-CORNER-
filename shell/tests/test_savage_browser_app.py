"""Behavioral verification for Savage Browser Unified App Coordinator."""

import unittest
from pathlib import Path
import sys

SHELL_DIR = Path(__file__).resolve().parent.parent
if str(SHELL_DIR) not in sys.path:
    sys.path.insert(0, str(SHELL_DIR))

from savage_browser_app import SavageBrowserApp


class TestSavageBrowserApp(unittest.TestCase):
    def setUp(self) -> None:
        self.app = SavageBrowserApp()

    def test_app_initialization(self) -> None:
        self.assertIsNotNone(self.app.active_tab_id)
        self.assertEqual(len(self.app.tab_manager.tabs), 1)
        active = self.app.tab_manager.get_active_tab()
        self.assertIsNotNone(active)
        self.assertEqual(active.url, "https://savage-tech-corner.io")

    def test_navigate_and_cache(self) -> None:
        url = "https://savage-tech-corner.io/dashboard"
        res = self.app.navigate(url)
        self.assertEqual(res["status"], "loading")
        self.assertEqual(res["url"], url)
        self.assertEqual(self.app.cache.get(f"last_url_{self.app.active_tab_id}"), url)

    def test_tab_lifecycle(self) -> None:
        new_tab_id = self.app.open_tab("https://savage-tech-corner.io/search", "Search")
        self.assertEqual(len(self.app.tab_manager.tabs), 2)
        self.assertEqual(self.app.active_tab_id, new_tab_id)

        self.app.close_tab(new_tab_id)
        self.assertEqual(len(self.app.tab_manager.tabs), 1)

    def test_bookmark_and_summarize(self) -> None:
        bm = self.app.bookmark_current()
        self.assertEqual(bm["status"], "saved")

        summary = self.app.ai_summarize_current("Custom article content")
        self.assertIn("summary", summary["summary"])

    def test_full_state_rendering(self) -> None:
        state = self.app.get_full_state()
        self.assertIn("ui", state)
        self.assertEqual(state["backend_health"]["status"], "HEALTHY")


if __name__ == "__main__":
    unittest.main()
