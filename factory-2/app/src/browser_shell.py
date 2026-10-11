"""Soul browser-style shell architecture for Savage Browser.

Modern Luminescence Shell UI 2.0:
- Rebuilt with modern translucent glassmorphism theme, dynamic address omnibox,
  pill navigation buttons, tabs badge indicator, and quick actions drawer.
- Added Modern UI 2.0 specs: blur effects, floating quick-actions bar, AI assistant button,
  and dark theme / OLED color palettes.
- Fundamentally preserves core contracts: WebViewBridge, WebViewHolder, and action_* buttons.
"""

from __future__ import annotations

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

    def invoke(self, name: str, *args: Any, request_id: Optional[str] = None, **kwargs: Any) -> Any:
        if name not in self._exported_methods:
            raise KeyError(f"No bridge handler registered for '{name}'")
        res = self._exported_methods[name](*args, **kwargs)
        self.message_log.append({
            "request_id": request_id or f"req-{len(self.message_log) + 1:04d}",
            "method": name,
            "result": res,
        })
        return res


class WebViewHolder:
    """Manages web view settings, URL rendering, tab history, and downloads."""

    def __init__(self) -> None:
        self.current_url: str = "about:blank"
        self.history: List[str] = []
        self.history_index: int = -1
        self.dom_storage_enabled: bool = True
        self.javascript_enabled: bool = True
        self.user_agent: str = "SavageBrowser/2.0 (Linux; Android 14; Mobile; ModernLuminescenceUI)"
        self.downloads: List[Dict[str, Any]] = []
        self.progress: int = 100
        self.title: str = "New Tab"

    def load_url(self, url: str) -> Dict[str, Any]:
        if not url or not (url.startswith("http://") or url.startswith("https://") or url.startswith("about:")):
            raise ValueError(f"Invalid webview URL scheme: '{url}'")
        self.current_url = url
        self.history = self.history[: self.history_index + 1]
        self.history.append(url)
        self.history_index += 1
        self.title = url.split("//")[-1].split("/")[0] if "://" in url else url
        return {"status": "loaded", "url": self.current_url, "title": self.title}

    def can_go_back(self) -> bool:
        return self.history_index > 0

    def can_go_forward(self) -> bool:
        return self.history_index < len(self.history) - 1

    def go_back(self) -> Optional[str]:
        if not self.can_go_back():
            return None
        self.history_index -= 1
        self.current_url = self.history[self.history_index]
        return self.current_url

    def go_forward(self) -> Optional[str]:
        if not self.can_go_forward():
            return None
        self.history_index += 1
        self.current_url = self.history[self.history_index]
        return self.current_url

    def trigger_download(self, url: str, mime_type: str = "application/octet-stream") -> Dict[str, Any]:
        dl = {"url": url, "mime_type": mime_type, "status": "queued"}
        self.downloads.append(dl)
        return dl


class ModernLuminescenceUITheme:
    """Design tokens and layout metrics for the Modern Luminescence Shell UI 2.0."""

    THEME_NAME = "ModernLuminescenceGlass2"
    ACCENT_COLOR = "#6366F1"  # Indigo Electric
    ACCENT_CYAN = "#06B6D4"   # Cyber Cyan Glow
    BACKGROUND_DARK = "#090D16"
    SURFACE_BLUR = "backdrop-filter: blur(24px)"
    BORDER_RADIUS = "18dp"
    ELEVATION = "10dp"

    @classmethod
    def get_layout_spec(cls) -> Dict[str, Any]:
        return {
            "version": "2.0.0",
            "omnibox": {
                "style": "floating_pill_glass",
                "security_indicator": "lock_icon_verified",
                "corner_radius": "28dp",
                "background": "rgba(30, 41, 59, 0.75)",
                "border": "1px solid rgba(255, 255, 255, 0.12)",
                "shadow": "0 8px 32px 0 rgba(0, 0, 0, 0.37)",
            },
            "bottom_bar": {
                "style": "glass_dock_floating",
                "blur": cls.SURFACE_BLUR,
                "background": "rgba(15, 23, 42, 0.85)",
                "elevation": cls.ELEVATION,
                "buttons": [
                    "btn_back",
                    "btn_forward",
                    "btn_home",
                    "btn_tabs",
                    "btn_refresh",
                    "btn_menu",
                    "btn_ai_assistant",
                ],
            },
            "quick_actions_dock": {
                "style": "slide_dock",
                "actions": ["reader_mode", "bookmark", "share", "ai_summary"],
                "glow_accent": cls.ACCENT_CYAN,
            },
            "tabs_badge": {
                "style": "counter_capsule_glow",
                "color": cls.ACCENT_COLOR,
                "glow": f"0 0 12px {cls.ACCENT_COLOR}",
            },
        }


class SoulBrowserShell:
    """Modern Luminescence Browser Android shell coordinator.

    Retains the exact same fundamental contract and button IDs while presenting
    a modern streamlined UI layout state tree.
    """

    def __init__(self) -> None:
        self.webview = WebViewHolder()
        self.bridge = WebViewBridge()
        self.theme = ModernLuminescenceUITheme()
        self.buttons: Dict[str, Callable[..., Any]] = {}
        self.tab_count: int = 1
        self.menu_open: bool = False
        self.ai_drawer_open: bool = False
        self._setup_ui()
        self._setup_bridge()

    def _setup_ui(self) -> None:
        self.buttons["btn_back"] = self.action_back
        self.buttons["btn_forward"] = self.action_forward
        self.buttons["btn_refresh"] = self.action_refresh
        self.buttons["btn_home"] = self.action_home
        self.buttons["btn_menu"] = self.action_menu
        self.buttons["btn_tabs"] = self.action_tabs
        self.buttons["btn_ai_assistant"] = self.action_ai_assistant

    def _setup_bridge(self) -> None:
        self.bridge.register_interface("getAppVersion", lambda: "1.0.0-soul")
        self.bridge.register_interface("getUIVersion", lambda: "2.0.0-lighting-modern")
        self.bridge.register_interface("postFeedback", lambda msg: f"Received: {msg}")

    def click_button(self, button_id: str) -> Any:
        if button_id not in self.buttons:
            raise RuntimeError(f"Dead button error: '{button_id}' has no registered handler")
        return self.buttons[button_id]()

    def action_back(self) -> Dict[str, Any]:
        url = self.webview.go_back()
        return {
            "action": "back",
            "url": url,
            "can_go_back": self.webview.can_go_back(),
            "can_go_forward": self.webview.can_go_forward(),
        }

    def action_forward(self) -> Dict[str, Any]:
        url = self.webview.go_forward() or self.webview.current_url
        return {
            "action": "forward",
            "url": url,
            "can_go_back": self.webview.can_go_back(),
            "can_go_forward": self.webview.can_go_forward(),
        }

    def action_refresh(self) -> Dict[str, Any]:
        return {
            "action": "refresh",
            "url": self.webview.current_url,
            "progress_restarted": True,
        }

    def action_home(self) -> Dict[str, Any]:
        res = self.webview.load_url("https://savage-tech-corner.io")
        return {
            "action": "home",
            "status": res["status"],
            "url": res["url"],
        }

    def action_menu(self) -> Dict[str, Any]:
        self.menu_open = not self.menu_open
        return {
            "action": "menu",
            "open": self.menu_open,
            "items": [
                "Bookmarks",
                "History",
                "Downloads",
                "Desktop Site",
                "Settings",
                "AI Assistant",
                "Privacy Center",
            ],
        }

    def action_tabs(self) -> Dict[str, Any]:
        return {
            "action": "tabs",
            "count": self.tab_count,
            "active_url": self.webview.current_url,
            "ui_mode": "grid_switcher_cards",
        }

    def action_ai_assistant(self) -> Dict[str, Any]:
        self.ai_drawer_open = not self.ai_drawer_open
        return {
            "action": "ai_assistant",
            "open": self.ai_drawer_open,
            "url": self.webview.current_url,
            "mode": "smart_summary_and_qna",
        }

    def render_modern_ui_state(self) -> Dict[str, Any]:
        """Renders the complete modern lighting UI state hierarchy."""
        return {
            "theme": self.theme.get_layout_spec(),
            "omnibox": {
                "display_url": self.webview.current_url,
                "title": self.webview.title,
                "is_secure": self.webview.current_url.startswith("https://"),
                "progress": self.webview.progress,
            },
            "controls": {
                "back_enabled": self.webview.can_go_back(),
                "forward_enabled": self.webview.can_go_forward(),
                "tabs_badge": self.tab_count,
                "menu_active": self.menu_open,
                "ai_drawer_active": self.ai_drawer_open,
            },
        }
