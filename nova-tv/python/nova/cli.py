"""Shared entry point for every script.

Each script in ``scripts/`` is a few lines: it registers its tools and calls
:func:`main`. That keeps 50+ commands consistent — same flags, same output shape,
same error handling — instead of 50 little programs that each behave differently.
"""
from __future__ import annotations

import argparse
import json
import sys
import traceback
from pathlib import Path
from typing import Any, Optional

from .config import ROOT, Settings, load_settings, redacted_view
from .registry import REGISTRY, Registry


def build_parser(title: str, description: str, registry: Optional[Registry] = None) -> argparse.ArgumentParser:
    registry = registry or REGISTRY
    parser = argparse.ArgumentParser(prog=title, description=description)
    parser.add_argument("--json", action="store_true", help="machine-readable output")
    parser.add_argument("--verbose", "-v", action="store_true", help="include tracebacks on failure")
    sub = parser.add_subparsers(dest="command")
    for name in registry.names():
        tool = registry.get(name)
        child = sub.add_parser(name, help=tool.summary)
        child.add_argument("--json", action="store_true", help="machine-readable output")
        child.add_argument("--verbose", "-v", action="store_true")
        for flag in getattr(tool.func, "__nova_flags__", []):
            child.add_argument(*flag[0], **flag[1])
    return parser


def _flags(*spec):
    """Declare CLI flags on a tool function."""

    def decorator(func):
        func.__nova_flags__ = list(spec)  # type: ignore[attr-defined]
        return func

    return decorator


def run(
    name: str,
    args: argparse.Namespace,
    *,
    registry: Optional[Registry] = None,
    settings: Optional[Settings] = None,
) -> int:
    registry = registry or REGISTRY
    tool = registry.get(name)
    settings = settings or load_settings()
    kwargs: dict[str, Any] = {}
    for spec in getattr(tool.func, "__nova_flags__", []):
        option = spec[0][0]
        dest = option.lstrip("-").replace("-", "_")
        if hasattr(args, dest):
            kwargs[dest] = getattr(args, dest)
    if tool.needs_github:
        kwargs.setdefault("settings", settings)
    elif tool.needs_ai:
        kwargs.setdefault("settings", settings)
    elif tool.needs_network:
        kwargs.setdefault("settings", settings)
    try:
        result = tool.func(**kwargs)
    except Exception as error:  # noqa: BLE001 - the CLI must report, not crash
        payload = {"ok": False, "tool": name, "error": str(error), "type": type(error).__name__}
        if getattr(args, "verbose", False):
            payload["traceback"] = traceback.format_exc()
        print(json.dumps(payload, indent=2) if getattr(args, "json", False) else f"error: {error}")
        return 1
    if getattr(args, "json", False):
        print(json.dumps({"ok": True, "tool": name, "result": result}, indent=2, default=str))
    else:
        _pretty(result)
    return 0


def _pretty(result: Any) -> None:
    if result is None:
        print("done")
    elif isinstance(result, (str, int, float)):
        print(result)
    elif isinstance(result, dict):
        for key, value in result.items():
            if isinstance(value, (list, dict)):
                print(f"{key}:")
                print(json.dumps(value, indent=2, default=str))
            else:
                print(f"{key}: {value}")
    elif isinstance(result, list):
        for item in result:
            print(f"- {item}" if not isinstance(item, (dict, list)) else json.dumps(item, default=str))
    else:
        print(result)


def main(script_file: str, description: str, registry: Optional[Registry] = None) -> int:
    """Standard main() for every script.

    A script with exactly one tool runs it when no subcommand is given. That is
    the common case, and requiring `nova qa_tests.py tests` for a single-purpose
    script is friction that also hid whether the tool ever actually ran.
    """
    registry = registry or REGISTRY
    title = Path(script_file).stem
    parser = build_parser(title, description, registry)
    args = parser.parse_args()
    command = getattr(args, "command", None)
    if not command:
        names = registry.names()
        if len(names) != 1:
            parser.print_help()
            return 0
        command = names[0]
    return run(command, args, registry=registry)


def settings_or_none() -> Settings:
    return load_settings()
