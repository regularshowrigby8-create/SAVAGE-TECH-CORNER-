"""Savage Tech Hub Application Main Entry Point."""

from typing import Any, Dict

try:
    from .device_service import DeviceService
    from .ui_controller import UIController
except ImportError:
    from device_service import DeviceService
    from ui_controller import UIController


class SavageApp:
    """The central application coordinator for Savage Tech Hub."""

    def __init__(self) -> None:
        self.device_service = DeviceService()
        self.ui = UIController()
        self._setup_ui()

    def _setup_ui(self) -> None:
        self.ui.register_button("btn_list_devices", "List Devices", self.action_list_devices)
        self.ui.register_button("btn_run_diagnostics", "Run Diagnostics", self.action_run_diagnostics)
        self.ui.register_button("btn_refresh_status", "Refresh Status", self.action_refresh_status)

    def action_list_devices(self) -> Dict[str, Any]:
        devices = self.device_service.list_devices()
        return {"action": "list_devices", "count": len(devices), "devices": devices}

    def action_run_diagnostics(self, device_id: str = "dev-01") -> Dict[str, Any]:
        return self.device_service.run_diagnostics(device_id)

    def action_refresh_status(self) -> Dict[str, Any]:
        return {"status": "all_systems_operational", "active_nodes": 3}


def build_app() -> SavageApp:
    return SavageApp()


if __name__ == "__main__":
    app = build_app()
    print("Savage Tech Hub initialized.")
    print("Listing devices:", app.ui.click_button("btn_list_devices"))
    print("Running diagnostics:", app.ui.click_button("btn_run_diagnostics", "dev-01"))
