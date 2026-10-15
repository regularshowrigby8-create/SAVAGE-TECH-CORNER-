#!/usr/bin/env python3
"""The sealed environment's MCP server: every tool in `toolkit.py`, over the protocol AI clients speak.

Runs as a stdio JSON-RPC 2.0 server implementing the Model Context Protocol handshake
(`initialize`, `notifications/initialized`, `tools/list`, `tools/call`, `ping`) plus `resources/list`
and `prompts/list` so a client that asks for them gets a real answer instead of an error. No package
is installed to run it: it is the standard library and this file.

    # as an MCP server (what a client such as Claude Code, Codex or any MCP host runs)
    python3 sealed-factories/runtime/toolserver.py --root <workspace>

    # directory of everything it offers, without a client
    python3 sealed-factories/runtime/toolserver.py --root . --list

Server configuration for a client:

    {"mcpServers": {"sealed-factory-tools": {"command": "python3",
      "args": ["sealed-factories/runtime/toolserver.py", "--root", "."]}}}

The server is the same layer agents use in-process, bound to one workspace root; a call that cannot
be carried out returns a JSON-RPC error / `isError` result with the reason, never an invented value.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from toolkit import ToolError, Workspace, build_registry, tool_names  # noqa: E402

PROTOCOL_VERSIONS = ("2025-06-18", "2025-03-26", "2024-11-05")
SERVER_NAME = "sealed-factory-tools"
SERVER_VERSION = "1.0.0"


def describe(registry: dict) -> list[dict]:
    return [{"name": name,
             "description": registry[name]["description"],
             "inputSchema": registry[name]["schema"]}
            for name in tool_names(registry)]


def _call(registry: dict, name: str, arguments: dict) -> dict:
    if name not in registry:
        raise ToolError(f"no tool named {name!r}; ask for tools/list")
    tool = registry[name]["fn"]
    allowed = set(registry[name]["schema"]["properties"])
    unknown = set(arguments) - allowed
    if unknown:
        raise ToolError(f"{name} does not take {', '.join(sorted(unknown))}")
    result = tool(**arguments)
    return {"content": [{"type": "text", "text": json.dumps(result, indent=1, sort_keys=True)}],
            "structuredContent": result, "isError": False}


def handle(message: dict, registry: dict, state: dict) -> dict | None:
    method = message.get("method")
    identifier = message.get("id")

    if method == "initialize":
        requested = (message.get("params") or {}).get("protocolVersion")
        version = requested if requested in PROTOCOL_VERSIONS else PROTOCOL_VERSIONS[-1]
        state["initialised"] = True
        return {"jsonrpc": "2.0", "id": identifier, "result": {
            "protocolVersion": version,
            "capabilities": {"tools": {"listChanged": False}, "resources": {"listChanged": False}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION,
                           "title": "Sealed factory tools"},
            "instructions": ("Tools for building, testing and analysing software in any language, "
                             "implemented inside this sealed component. Every tool works offline "
                             "except the network and model tools, which fail with a reason when "
                             "nothing is reachable.")}}

    if method in ("notifications/initialized", "notifications/cancelled"):
        state["initialised"] = True
        return None

    if method == "ping":
        return {"jsonrpc": "2.0", "id": identifier, "result": {}}

    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": identifier,
                "result": {"tools": describe(registry)}}

    if method == "tools/call":
        params = message.get("params") or {}
        name = params.get("name", "")
        arguments = params.get("arguments") or {}
        try:
            return {"jsonrpc": "2.0", "id": identifier, "result": _call(registry, name, arguments)}
        except ToolError as problem:
            return {"jsonrpc": "2.0", "id": identifier,
                    "result": {"content": [{"type": "text", "text": str(problem)}], "isError": True}}
        except Exception as problem:  # a tool's own bug is reported, not hidden
            return {"jsonrpc": "2.0", "id": identifier,
                    "result": {"content": [{"type": "text",
                                            "text": f"{type(problem).__name__}: {problem}"}],
                               "isError": True}}

    if method == "resources/list":
        return {"jsonrpc": "2.0", "id": identifier, "result": {"resources": []}}

    if method == "prompts/list":
        return {"jsonrpc": "2.0", "id": identifier, "result": {"prompts": []}}

    if identifier is None:
        return None
    return {"jsonrpc": "2.0", "id": identifier,
            "error": {"code": -32601, "message": f"method not found: {method}"}}


def serve(root: str, gateway_url: str = "") -> int:
    registry = build_registry(Workspace(root), gateway_url)
    state: dict = {}
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError:
            sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": None, "error": {
                "code": -32700, "message": "the request is not valid JSON"}}) + "\n")
            sys.stdout.flush()
            continue
        reply = handle(message, registry, state)
        if reply is not None:
            sys.stdout.write(json.dumps(reply) + "\n")
            sys.stdout.flush()
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Sealed factory MCP tool server")
    parser.add_argument("--root", default=".", help="workspace root every tool is bound to")
    parser.add_argument("--gateway", default="", help="unified model gateway URL, for model.ask")
    parser.add_argument("--list", action="store_true", help="print the tool directory and exit")
    parser.add_argument("--call", default="", help="call one tool and print its result")
    parser.add_argument("--arguments", default="{}", help="JSON arguments for --call")
    args = parser.parse_args()

    if args.list:
        registry = build_registry(Workspace(args.root), args.gateway)
        for entry in describe(registry):
            print(f'{entry["name"]:<16} {entry["description"]}')
        print(f"\n{len(registry)} tools")
        return 0

    if args.call:
        registry = build_registry(Workspace(args.root), args.gateway)
        try:
            print(json.dumps(_call(registry, args.call, json.loads(args.arguments)), indent=1))
            return 0
        except ToolError as problem:
            print(f"tool refused: {problem}", file=sys.stderr)
            return 1

    return serve(args.root, args.gateway)


if __name__ == "__main__":
    sys.exit(main())
