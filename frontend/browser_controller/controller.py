"""Android Browser Frontend: Browser Controller Module."""

from typing import Any, Callable, Dict, List


class BrowserController:
    """Coordinates navigation actions, address bar events, and user triggers."""

    def __init__(self) -> None:
        self.action_listeners: Dict[str, Callable[..., Any]] = {}

    def register_action(self, action_name: str, handler: Callable[..., Any]) -> None:
        self.action_listeners[action_name] = handler

    def dispatch(self, action_name: str, *args: Any, **kwargs: Any) -> Any:
        if action_name not in self.action_listeners:
            raise KeyError(f"Action '{action_name}' has no registered handler")
        return self.action_listeners[action_name](*args, **kwargs)
