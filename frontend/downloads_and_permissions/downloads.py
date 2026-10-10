"""Android Browser Frontend: Downloads and Permissions Module."""

from typing import Any, Dict, List, Set


class DownloadManager:
    """Manages file downloads, Scoped Storage integration, and runtime permissions."""

    REQUIRED_PERMISSIONS: Set[str] = {
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
    }

    def __init__(self) -> None:
        self.granted_permissions: Set[str] = set(self.REQUIRED_PERMISSIONS)
        self.active_downloads: List[Dict[str, Any]] = []

    def request_permission(self, permission: str) -> bool:
        self.granted_permissions.add(permission)
        return True

    def start_download(self, url: str, destination: str) -> Dict[str, Any]:
        task = {"url": url, "destination": destination, "status": "IN_PROGRESS", "bytes_downloaded": 0}
        self.active_downloads.append(task)
        return task
