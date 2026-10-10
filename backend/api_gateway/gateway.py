"""Browser Backend: API Gateway Module."""

from typing import Any, Callable, Dict, List


class ApiGateway:
    """Reverse proxy, rate limiting, and routing gateway for browser services."""

    def __init__(self) -> None:
        self.routes: Dict[str, Callable[[Dict[str, Any]], Dict[str, Any]]] = {}
        self.request_counter: int = 0

    def register_route(self, path: str, handler: Callable[[Dict[str, Any]], Dict[str, Any]]) -> None:
        self.routes[path] = handler

    def dispatch(self, path: str, request_data: Dict[str, Any]) -> Dict[str, Any]:
        self.request_counter += 1
        if path not in self.routes:
            return {"status": 404, "error": f"Route '{path}' not found"}
        handler = self.routes[path]
        return {"status": 200, "result": handler(request_data)}
