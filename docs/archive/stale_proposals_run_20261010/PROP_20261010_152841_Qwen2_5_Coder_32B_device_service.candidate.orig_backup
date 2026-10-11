"""Device and diagnostic service for Savage Tech Hub."""

from typing import Any, Dict, List


class DeviceService:
    """Provides hardware specs, benchmark comparisons, and diagnostic status."""

    def __init__(self) -> None:
        self.devices: List[Dict[str, Any]] = [
            {"id": "dev-01", "name": "Workstation Rig", "ram_gb": 64, "vram_gb": 24, "status": "online"},
            {"id": "dev-02", "name": "Edge AI Box", "ram_gb": 16, "vram_gb": 8, "status": "idle"},
            {"id": "dev-03", "name": "Mobile Lab Phone", "ram_gb": 12, "vram_gb": 0, "status": "active"},
        ]

    def list_devices(self) -> List[Dict[str, Any]]:
        return list(self.devices)

    def get_device(self, device_id: str) -> Dict[str, Any]:
        for dev in self.devices:
            if dev["id"] == device_id:
                return dict(dev)
        raise KeyError(f"Device {device_id} not found")

    def run_diagnostics(self, device_id: str) -> Dict[str, Any]:
        device = self.get_device(device_id)
        return {
            "device_id": device["id"],
            "device_name": device["name"],
            "memory_status": "healthy" if device["ram_gb"] >= 16 else "warning",
            "vram_status": "ready" if device["vram_gb"] >= 8 else "cpu_fallback",
            "passed": True,
        }
