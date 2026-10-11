import sys
import unittest
from pathlib import Path

SHELL_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SHELL_DIR))

from savage_browser_app import SavageBrowserApp


class TestSavageBrowserApp(unittest.TestCase):
    def setUp(self):
        self.app = SavageBrowserApp()

    def test_app_initialization(self):
        self.assertIsNotNone(self.app.active_tab_id)
        self.assertEqual(len(self.app.tab_manager.tabs), 2)  # tab-1 and tab-main

    def test_navigation_and_caching(self):
        res = self.app.navigate("https://savage-tech-corner.io/search")
        self.assertEqual(res["status"], "loading")
        self.assertEqual(self.app.cache.get(f"last_url_{self.app.active_tab_id}"), "https://savage-tech-corner.io/search")

    def test_tab_lifecycle(self):
        new_tab_id = self.app.open_tab("https://savage-tech-corner.io/settings", "Settings")
        self.assertEqual(self.app.active_tab_id, new_tab_id)
        self.app.close_tab(new_tab_id)
        self.assertNotEqual(self.app.active_tab_id, new_tab_id)

    def test_bookmark_and_database(self):
        self.app.navigate("https://savage-tech-corner.io/repo")
        bm = self.app.bookmark_current()
        self.assertEqual(bm["status"], "saved")
        rows = self.app.db.query("bookmarks", lambda r: r["url"] == "https://savage-tech-corner.io/repo")
        self.assertTrue(len(rows) >= 1)

    def test_ai_summarization_and_worker(self):
        self.app.navigate("https://savage-tech-corner.io/article")
        summary_res = self.app.ai_summarize_current("Deep learning architectures in modern browser clients.")
        self.assertIn("summary", summary_res)
        task = self.app.worker_queue.process_next()
        self.assertIsNotNone(task)
        self.assertEqual(task["name"], "ai_page_processed")

    def test_full_state_rendering(self):
        state = self.app.get_full_state()
        self.assertIn("ui", state)
        self.assertIn("backend_health", state)
        self.assertEqual(state["backend_health"]["status"], "HEALTHY")

    def test_continuous_webview_crawler(self):
        res = self.app.crawler.simulate_search("Savage Browser APK")
        self.assertTrue(len(res) >= 1)
        self.assertTrue(len(self.app.crawler.crawl_queue) >= 1)
        self.app.crawler.stop_continuous_crawl()


if __name__ == "__main__":
    unittest.main()
