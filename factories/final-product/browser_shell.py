"""Soul Browser Shell application core."""

from typing import Any, Callable, Dict, List, Optional


class WebViewBridge:
    """JavaScript-to-Native bridge interface for Soul Browser."""

    def __init__(self) -> None:
        self._exported_methods: Dict[str, Callable[..., Any]] = {}
        self.message_log: List[Dict[str, Any]] = []

    def register_interface(self, name: str, handler: Callable[..., Any]) -> None:
        if not callable(handler):
            raise ValueError(f"Bridge handler for '{name}' must be callable")
        self._exported_methods[name] = handler

    def invoke(self, name: str, *args: Any, **kwargs: Any) -> Any:
        if name not in self._exported_methods:
            raise KeyError(f"No bridge handler registered for '{name}'")
        res = self._exported_methods[name](*args, **kwargs)
        self.message_log.append({"method": name, "result": res})
        return res


class WebViewHolder:
    """Manages web view settings, URL rendering, tab history, and downloads."""

    def __init__(self) -> None:
        self.current_url: str = "about:blank"
        self.history: List[str] = []
        self.history_index: int = -1
        self.dom_storage_enabled: bool = True
        self.javascript_enabled: bool = True
        self.user_agent: str = "SoulBrowser/1.0 (Linux; Android 14; Mobile)"
        self.downloads: List[Dict[str, Any]] = []

    def load_url(self, url: str) -> Dict[str, Any]:
        if not url or not (url.startswith("http://") or url.startswith("https://") or url.startswith("about:")):
            raise ValueError(f"Invalid webview URL scheme: '{url}'")
        self.current_url = url
        self.history = self.history[: self.history_index + 1]
        self.history.append(url)
        self.history_index += 1
        return {"status": "loaded", "url": self.current_url}

    def can_go_back(self) -> bool:
        return self.history_index > 0

    def go_back(self) -> Optional[str]:
        if not self.can_go_back():
            return None
        self.history_index -= 1
        self.current_url = self.history[self.history_index]
        return self.current_url

    def trigger_download(self, url: str, mime_type: str = "application/octet-stream") -> Dict[str, Any]:
        dl = {"url": url, "mime_type": mime_type, "status": "queued"}
        self.downloads.append(dl)
        return dl


class SoulBrowserShell:
    """Soul Browser Android shell coordinator."""

    def __init__(self) -> None:
        self.webview = WebViewHolder()
        self.bridge = WebViewBridge()
        self.buttons: Dict[str, Callable[..., Any]] = {}
        self._setup_ui()
        self._setup_bridge()

    def _setup_ui(self) -> None:
        self.buttons["btn_back"] = self.action_back
        self.buttons["btn_forward"] = self.action_forward
        self.buttons["btn_refresh"] = self.action_refresh
        self.buttons["btn_home"] = self.action_home
        self.buttons["btn_menu"] = self.action_menu
        self.buttons["btn_tabs"] = self.action_tabs

    def _setup_bridge(self) -> None:
        self.bridge.register_interface("getAppVersion", lambda: "1.0.0-soul")
        self.bridge.register_interface("postFeedback", lambda msg: f"Received: {msg}")

    def click_button(self, button_id: str) -> Any:
        if button_id not in self.buttons:
            raise RuntimeError(f"Dead button error: '{button_id}' has no registered handler")
        return self.buttons[button_id]()

    def action_back(self) -> Dict[str, Any]:
        url = self.webview.go_back()
        return {"action": "back", "url": url}

    def action_forward(self) -> Dict[str, Any]:
        return {"action": "forward", "url": self.webview.current_url}

    def action_refresh(self) -> Dict[str, Any]:
        return {"action": "refresh", "url": self.webview.current_url}

    def action_home(self) -> Dict[str, Any]:
        return self.webview.load_url("https://savage-tech-corner.io")

    def action_menu(self) -> Dict[str, Any]:
        return {"action": "menu", "open": True}

    def action_tabs(self) -> Dict[str, Any]:
        return {"action": "tabs", "count": 1}
