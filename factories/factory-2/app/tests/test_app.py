"""Behavioral tests for Savage Tech Hub application."""

import sys
import unittest
from pathlib import Path

# Add app src to sys.path
SRC_DIR = Path(__file__).resolve().parent.parent / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from device_service import DeviceService
from main import SavageApp, build_app
from ui_controller import UIController


class TestSavageApp(unittest.TestCase):
    def setUp(self) -> None:
        self.app = build_app()

    def test_app_initialization(self) -> None:
        self.assertIsInstance(self.app, SavageApp)
        self.assertIsInstance(self.app.device_service, DeviceService)
        self.assertIsInstance(self.app.ui, UIController)

    def test_all_buttons_have_handlers(self) -> None:
        buttons = self.app.ui.get_registered_buttons()
        self.assertGreaterEqual(len(buttons), 3)
        for b in buttons:
            self.assertTrue(self.app.ui.is_button_handled(b["id"]))

    def test_button_clicks_succeed(self) -> None:
        res_devices = self.app.ui.click_button("btn_list_devices")
        self.assertEqual(res_devices["action"], "list_devices")
        self.assertEqual(res_devices["count"], 3)

        res_diag = self.app.ui.click_button("btn_run_diagnostics", "dev-01")
        self.assertEqual(res_diag["device_id"], "dev-01")
        self.assertTrue(res_diag["passed"])

        res_status = self.app.ui.click_button("btn_refresh_status")
        self.assertEqual(res_status["status"], "all_systems_operational")

    def test_dead_button_fails_closed(self) -> None:
        ui = UIController()
        with self.assertRaises(RuntimeError):
            ui.click_button("unhandled_btn")


if __name__ == "__main__":
    unittest.main()
