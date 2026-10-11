"""Savage Browser Unified Architecture System.

Integrates:
- Frontend: WebViewEngine, TabManager, BrowserScreen, BrowserApiClient, DownloadManager, LocalStorageStore, SearchProviderRegistry, BrowserController
- Backend: ApiGateway, DatabaseManager, CacheLayer, SearchOrchestrator, UserSyncService, BackgroundWorkerQueue, MonitoringAndSecurityService, BrowserAIService
- Shell: Hardened WebView Bridge, Navigation controls, Protocol Enforcement
"""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

ROOT_DIR = Path(__file__).resolve().parent.parent

# Frontend imports
sys.path.insert(0, str(ROOT_DIR / "frontend" / "webview_engine"))
sys.path.insert(0, str(ROOT_DIR / "frontend" / "tabs_and_navigation"))
sys.path.insert(0, str(ROOT_DIR / "frontend" / "compose_user_interface"))
sys.path.insert(0, str(ROOT_DIR / "frontend" / "browser_controller"))
sys.path.insert(0, str(ROOT_DIR / "frontend" / "api_client"))
sys.path.insert(0, str(ROOT_DIR / "frontend" / "downloads_and_permissions"))
sys.path.insert(0, str(ROOT_DIR / "frontend" / "local_storage"))
sys.path.insert(0, str(ROOT_DIR / "frontend" / "search_providers"))

# Backend imports
sys.path.insert(0, str(ROOT_DIR / "backend" / "api_gateway"))
sys.path.insert(0, str(ROOT_DIR / "backend" / "databases"))
sys.path.insert(0, str(ROOT_DIR / "backend" / "caching"))
sys.path.insert(0, str(ROOT_DIR / "backend" / "search_orchestration"))
sys.path.insert(0, str(ROOT_DIR / "backend" / "user_and_sync_services"))
sys.path.insert(0, str(ROOT_DIR / "backend" / "background_workers"))
sys.path.insert(0, str(ROOT_DIR / "backend" / "monitoring_and_security"))
sys.path.insert(0, str(ROOT_DIR / "backend" / "ai_services"))

from engine import WebViewEngine
from tabs import TabManager
from compose_ui import BrowserScreen
from controller import BrowserController
from client import BrowserApiClient
from downloads import DownloadManager
from storage import LocalStorageStore
from providers import SearchProviderRegistry
from webview_crawler import WebViewCrawler

from gateway import ApiGateway
from db import DatabaseManager
from cache import CacheLayer
from orchestrator import SearchOrchestrator
from sync_service import UserSyncService
from worker import BackgroundWorkerQueue
from security import MonitoringAndSecurityService
from ai_service import BrowserAIService


class SavageBrowserApp:
    """Production architecture tying together the frontend, backend, and native shell."""

    def __init__(self) -> None:
        # Backend Subsystem Initialization
        self.gateway = ApiGateway()
        self.db = DatabaseManager()
        self.cache = CacheLayer()
        self.search_orchestrator = SearchOrchestrator()
        self.sync_service = UserSyncService()
        self.worker_queue = BackgroundWorkerQueue()
        self.security_service = MonitoringAndSecurityService()
        self.ai_service = BrowserAIService()

        # Frontend Subsystem Initialization
        self.webview_engine = WebViewEngine()
        self.tab_manager = TabManager()
        self.ui_screen = BrowserScreen()
        self.controller = BrowserController()
        self.api_client = BrowserApiClient()
        self.download_manager = DownloadManager()
        self.local_storage = LocalStorageStore()
        self.search_registry = SearchProviderRegistry()
        self.crawler = WebViewCrawler()

        # Wire Backend Route Handlers to ApiGateway
        self._wire_api_gateway()

        # Wire Frontend Controller Actions
        self._wire_frontend_controller()

        # Wire and start continuous background crawler (lazy start on demand)
        self.controller.register_action("start_crawler", self.crawler.start_continuous_crawl)
        self.controller.register_action("stop_crawler", self.crawler.stop_continuous_crawl)

        # TabManager already initializes with an active home tab ('tab-1')
        active = self.tab_manager.get_active_tab()
        self.active_tab_id = active.tab_id if active else "tab-1"

    def _wire_api_gateway(self) -> None:
        self.gateway.register_route("/search", lambda q: self.search_orchestrator.orchestrate_search(q.get("q", "")))
        self.gateway.register_route("/sync", lambda d: self.sync_service.sync_bookmarks(d.get("bookmarks", [])))
        self.gateway.register_route("/ai/summarize", lambda p: self.ai_service.summarize_page(p.get("text", "")))
        self.gateway.register_route("/health", lambda _: self.security_service.get_health())

    def _wire_frontend_controller(self) -> None:
        self.controller.register_action("navigate", self.navigate)
        self.controller.register_action("new_tab", self.open_tab)
        self.controller.register_action("close_tab", self.close_tab)
        self.controller.register_action("bookmark", self.bookmark_current)
        self.controller.register_action("summarize", self.ai_summarize_current)

    def navigate(self, url: str) -> Dict[str, Any]:
        """Loads URL in active tab, enforces HTTPS schemes, and updates cache."""
        self.security_service.log_event("NAVIGATION_REQUEST", "INFO", {"url": url})
        result = self.webview_engine.load_url(url)
        self.cache.set(f"last_url_{self.active_tab_id}", url)
        self.local_storage.add_bookmark("Visited Page", url)
        return result

    def open_tab(self, url: str = "about:blank", title: str = "New Tab") -> str:
        tab_id = f"tab-{len(self.tab_manager.tabs) + 1}"
        tab = self.tab_manager.create_tab(tab_id, title, url)
        self.active_tab_id = tab.tab_id
        return tab.tab_id

    def close_tab(self, tab_id: str) -> None:
        self.tab_manager.close_tab(tab_id)
        active = self.tab_manager.get_active_tab()
        if active:
            self.active_tab_id = active.tab_id

    def bookmark_current(self) -> Dict[str, Any]:
        url = self.webview_engine.current_url
        self.local_storage.add_bookmark("Favorite", url)
        self.db.insert("bookmarks", {"tab_id": self.active_tab_id, "url": url})
        return {"status": "saved", "url": url}

    def ai_summarize_current(self, page_content: Optional[str] = None) -> Dict[str, Any]:
        url = self.webview_engine.current_url
        content_to_summarize = page_content or f"Content from {url}"
        summary = self.ai_service.summarize_page(url, content_to_summarize)
        self.worker_queue.enqueue("ai_page_processed", {"url": url})
        return {"summary": summary, "url": url}

    def get_full_state(self) -> Dict[str, Any]:
        """Renders Compose UI screen with synchronized backend health and tab hierarchy."""
        ui_state = {
            "url": self.webview_engine.current_url,
            "security": "secure" if self.webview_engine.current_url.startswith("https://") else "insecure",
            "progress": 100,
            "tabs_count": len(self.tab_manager.tabs),
            "can_go_back": True,
        }
        active_tab = self.tab_manager.get_active_tab()
        return {
            "ui": self.ui_screen.render_screen(ui_state),
            "backend_health": self.security_service.get_health(),
            "active_tab": {"id": active_tab.tab_id, "url": active_tab.url} if active_tab else None,
            "cached_bookmarks": self.local_storage.get_bookmarks(),
        }
