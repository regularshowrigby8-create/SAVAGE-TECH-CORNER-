"""Shared safety boundaries for read-only development jobs."""

import re
from pathlib import Path

FORBIDDEN_ARGS = frozenset(
    {
        "--fix",
        "--write",
        "--apply",
        "-w",
        "--update",
        "--upgrade",
        "assembleRelease",
        ":app:assembleRelease",
        "publish",
        "push",
        "commit",
        "reset",
        "clean",
        "ktlintFormat",
        "sign",
        "installDebug",
        "installRelease",
    }
)


def safe_relative(root: Path, value: str) -> Path:
    """Resolve a non-sensitive repository path without allowing traversal."""
    path = Path(value)
    if path.is_absolute() or "\\" in value or ":" in value:
        raise ValueError("Expected a repository-relative path")
    if not value or any(part in {"", ".", "..", ".git"} for part in value.split("/")):
        raise ValueError("Unsafe path component")
    result = (root / path).resolve()
    if not result.is_relative_to(root.resolve()):
        raise ValueError("Path escapes repository")
    return result


def validate_command(argv: list[str]) -> None:
    """Reject mutation/release/shell commands in the normal execution interface."""
    if len(argv) < 2 or any(
        not isinstance(arg, str) or not arg or "\x00" in arg for arg in argv
    ):
        raise ValueError("Command must be a nonempty argument array")
    if Path(argv[0]).name in {"sh", "bash", "zsh", "cmd", "powershell"}:
        raise ValueError("Shell dispatch is prohibited")
    for arg in argv:
        if arg.split("=", 1)[0] in FORBIDDEN_ARGS:
            raise ValueError(
                "Source mutation, installation or release command prohibited"
            )
    if all(arg.startswith("-") for arg in argv[1:]) and set(argv[1:]) <= {
        "--version",
        "-V",
        "--help",
        "-h",
    }:
        raise ValueError("Availability/version probes are not tool integrations")


def validate_tools(data: dict) -> list[dict]:
    """Require distinct named jobs with concrete commands and provenance."""
    tools = data.get("tools", [])
    if data.get("schema_version") != 1 or len(tools) < 50:
        raise ValueError(
            "Factory requires schema1 and at least50 concrete tool integrations"
        )
    ids = [tool.get("id") for tool in tools]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate tool identities are not extra tools")
    commands = [tuple(tool["command"]) for tool in tools]
    if len(commands) != len(set(commands)):
        raise ValueError("Identical commands are not additional tool integrations")
    for tool in tools:
        if not isinstance(tool["id"], str) or not re.fullmatch(
            r"[a-z0-9-]+", tool["id"]
        ):
            raise ValueError("Invalid tool identity")
        validate_command(tool["command"])
        if tool["source_writes"] or not tool["upstream"].startswith("https://"):
            raise ValueError("Tools need read-only commands and upstream provenance")
        if not 1 <= tool["timeout_seconds"] <= 1800:
            raise ValueError("Missing finite execution bound")
        if tool["kind"] not in {"check", "report", "search", "secret-scan"}:
            raise ValueError("Unknown result semantics")
    return tools
