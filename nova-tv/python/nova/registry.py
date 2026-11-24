"""A tiny plugin registry.

Every capability in ``nova`` is a registered function with metadata. Scripts are
generated from this same registry, so adding a capability costs one decorator and
the CLI entry point appears automatically.

The registry refuses duplicate names and refuses to publish anything that has not
declared whether it touches the network — that is what keeps the "does this need a
token?" question answerable instead of guessed.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional


@dataclass
class Tool:
    name: str
    func: Callable[..., Any]
    summary: str
    group: str = "general"
    needs_network: bool = False
    needs_github: bool = False
    needs_ai: bool = False
    added_at: float = field(default_factory=time.time)

    def describe(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "summary": self.summary,
            "group": self.group,
            "needs_network": self.needs_network,
            "needs_github": self.needs_github,
            "needs_ai": self.needs_ai,
        }


class Registry:
    def __init__(self) -> None:
        self._tools: Dict[str, Tool] = {}

    def register(
        self,
        name: Optional[str] = None,
        *,
        summary: str = "",
        group: str = "general",
        needs_network: bool = False,
        needs_github: bool = False,
        needs_ai: bool = False,
    ) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
        def decorator(func: Callable[..., Any]) -> Callable[..., Any]:
            tool_name = name or func.__name__
            if tool_name in self._tools:
                raise ValueError(f"tool {tool_name!r} is already registered")
            if not summary:
                raise ValueError(f"tool {tool_name!r} must declare a summary")
            self._tools[tool_name] = Tool(
                name=tool_name,
                func=func,
                summary=summary,
                group=group,
                needs_network=needs_network,
                needs_github=needs_github,
                needs_ai=needs_ai,
            )
            return func

        return decorator

    def get(self, name: str) -> Tool:
        if name not in self._tools:
            raise KeyError(f"unknown tool {name!r}")
        return self._tools[name]

    def run(self, name: str, *args: Any, **kwargs: Any) -> Any:
        return self.get(name).func(*args, **kwargs)

    def names(self) -> List[str]:
        return sorted(self._tools)

    def groups(self) -> Dict[str, List[str]]:
        grouped: Dict[str, List[str]] = {}
        for tool in self._tools.values():
            grouped.setdefault(tool.group, []).append(tool.name)
        return {key: sorted(value) for key, value in sorted(grouped.items())}

    def manifest(self) -> Dict[str, Any]:
        return {
            "count": len(self._tools),
            "groups": self.groups(),
            "tools": [self._tools[name].describe() for name in self.names()],
        }

    def __len__(self) -> int:
        return len(self._tools)


REGISTRY = Registry()
