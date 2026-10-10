"""Android Browser Frontend: Local Storage Module."""

from typing import Any, Dict, List, Optional


class LocalStorageStore:
    """Encrypted key-value preferences and history storage for Android frontend."""

    def __init__(self) -> None:
        self.preferences: Dict[str, Any] = {}
        self.bookmarks: List[Dict[str, str]] = []
        self.history_records: List[Dict[str, Any]] = []

    def set_preference(self, key: str, value: Any) -> None:
        self.preferences[key] = value

    def get_preference(self, key: str, default: Any = None) -> Any:
        return self.preferences.get(key, default)

    def add_bookmark(self, title: str, url: str) -> None:
        self.bookmarks.append({"title": title, "url": url})

    def get_bookmarks(self) -> List[Dict[str, str]]:
        return list(self.bookmarks)
