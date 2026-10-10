"""Android Browser Frontend: Tabs and Navigation Module."""

from typing import Any, Dict, List, Optional


class Tab:
    def __init__(self, tab_id: str, title: str = "New Tab", url: str = "about:blank") -> None:
        self.tab_id = tab_id
        self.title = title
        self.url = url
        self.history: List[str] = [url]
        self.history_index: int = 0


class TabManager:
    """Manages multi-tab session states, tab switching, and navigation history."""

    def __init__(self) -> None:
        self.tabs: Dict[str, Tab] = {}
        self.active_tab_id: Optional[str] = None
        self.create_tab("tab-1", "Home", "https://savage-tech-corner.io")

    def create_tab(self, tab_id: str, title: str, url: str) -> Tab:
        tab = Tab(tab_id, title, url)
        self.tabs[tab_id] = tab
        self.active_tab_id = tab_id
        return tab

    def close_tab(self, tab_id: str) -> Optional[str]:
        if tab_id in self.tabs:
            del self.tabs[tab_id]
        if self.tabs:
            self.active_tab_id = next(iter(self.tabs))
        else:
            self.active_tab_id = None
        return self.active_tab_id

    def get_active_tab(self) -> Optional[Tab]:
        return self.tabs.get(self.active_tab_id) if self.active_tab_id else None
