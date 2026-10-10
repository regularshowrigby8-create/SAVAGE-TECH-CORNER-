"""Browser Backend: Caching Module."""

from typing import Any, Dict, Optional
import time


class CacheLayer:
    """In-memory and Redis-compatible distributed cache abstraction."""

    def __init__(self) -> None:
        self.store: Dict[str, Dict[str, Any]] = {}

    def set(self, key: str, value: Any, ttl_seconds: int = 3600) -> None:
        self.store[key] = {"val": value, "expires_at": time.time() + ttl_seconds}

    def get(self, key: str) -> Optional[Any]:
        if key not in self.store:
            return None
        entry = self.store[key]
        if time.time() > entry["expires_at"]:
            del self.store[key]
            return None
        return entry["val"]
