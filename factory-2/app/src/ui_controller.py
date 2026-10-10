"""UI Controller for Savage Tech Hub application."""

from typing import Any, Callable, Dict, List


class UIController:
    """Manages view state, interactive elements, and button click dispatches."""

    def __init__(self) -> None:
        self.handlers: Dict[str, Callable[..., Any]] = {}
        self.button_registry: List[Dict[str, str]] = []
        self.logs: List[str] = []

    def register_button(self, button_id: str, label: str, handler: Callable[..., Any]) -> None:
        """Register a button with a non-empty handler ensuring no dead buttons."""
        if not callable(handler):
            raise ValueError(f"Button '{button_id}' must have a valid callable handler")
        self.handlers[button_id] = handler
        self.button_registry.append({"id": button_id, "label": label})

    def click_button(self, button_id: str, *args: Any, **kwargs: Any) -> Any:
        """Dispatches button click event to registered handler."""
        if button_id not in self.handlers:
            raise RuntimeError(f"Dead button detected: '{button_id}' has no registered handler")
        handler = self.handlers[button_id]
        result = handler(*args, **kwargs)
        self.logs.append(f"Button {button_id} executed successfully")
        return result

    def get_registered_buttons(self) -> List[Dict[str, str]]:
        return list(self.button_registry)

    def is_button_handled(self, button_id: str) -> bool:
        return button_id in self.handlers
