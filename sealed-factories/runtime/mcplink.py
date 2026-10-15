"""Binds installed MCP tool servers into the registry, and reports the ones that are not installed.

The catalogued MCP repositories are *imports*: their manifests are carried in `mcp-servers.json`, and
on a machine where their package (or binary) is already present they are bound as tools named
`mcp.<server>.<tool>`, with the schemas the server itself publishes - so an external AI client sees
one flat tool directory, exactly as the user asked for.

Nothing is installed to make this work, and nothing pretends: a server whose command is missing is
listed as unavailable with the command that is missing, never as a tool that exists.
"""

from __future__ import annotations

import importlib.util
import json
import shutil
import subprocess
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
CATALOGUE = HERE.parent / "mcp-servers.json"
HANDSHAKE_TIMEOUT = 20
CALL_TIMEOUT = 120


class McpLinkError(RuntimeError):
    pass


class StdioClient:
    """One MCP server, spoken to over stdin/stdout - the transport every host supports."""

    def __init__(self, command: list[str], cwd: str | None = None, env: dict | None = None):
        self.command = command
        self._lock = threading.Lock()
        self._counter = 0
        self.server_info: dict = {}
        try:
            self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                            stderr=subprocess.DEVNULL, cwd=cwd, env=env, text=True,
                                            bufsize=1)
        except OSError as problem:
            raise McpLinkError(f"could not start {' '.join(command)}: {problem}") from problem
        self._initialise()

    def _next_id(self) -> int:
        with self._lock:
            self._counter += 1
            return self._counter

    def _send(self, message: dict) -> None:
        if self.process.stdin is None:
            raise McpLinkError("the server's input stream is closed")
        self.process.stdin.write(json.dumps(message) + "\n")
        self.process.stdin.flush()

    def _read(self, want_id: int, timeout: float) -> dict:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.process.stdout is None:
                raise McpLinkError("the server's output stream is closed")
            line = self.process.stdout.readline()
            if not line:
                raise McpLinkError("the server exited before answering")
            line = line.strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except ValueError:
                continue
            if message.get("id") == want_id:
                return message
        raise McpLinkError(f"the server did not answer within {timeout}s")

    def call(self, method: str, params: dict | None = None, timeout: float = CALL_TIMEOUT) -> dict:
        identifier = self._next_id()
        self._send({"jsonrpc": "2.0", "id": identifier, "method": method, "params": params or {}})
        message = self._read(identifier, timeout)
        if "error" in message:
            raise McpLinkError(f"{method} failed: {message['error'].get('message', message['error'])}")
        return message.get("result", {})

    def _initialise(self) -> None:
        result = self.call("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                                          "clientInfo": {"name": "sealed-factory-runtime",
                                                         "version": "1.0.0"}},
                           timeout=HANDSHAKE_TIMEOUT)
        self.server_info = result.get("serverInfo", {})
        self._send({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def tools(self) -> list[dict]:
        return self.call("tools/list", {}, timeout=HANDSHAKE_TIMEOUT).get("tools", [])

    def call_tool(self, name: str, arguments: dict) -> dict:
        result = self.call("tools/call", {"name": name, "arguments": arguments})
        text = "\n".join(part.get("text", "") for part in result.get("content", [])
                         if part.get("type") == "text")
        if result.get("isError"):
            raise McpLinkError(text or "the server reported an error")
        return {"server": self.server_info.get("name", "unknown"), "text": text[:40_000],
                "structured": result.get("structuredContent")}

    def close(self) -> None:
        try:
            self.process.terminate()
            self.process.wait(timeout=5)
        except (OSError, subprocess.SubprocessError):
            self.process.kill()


def load_catalogue() -> list[dict]:
    if not CATALOGUE.is_file():
        return []
    try:
        payload = json.loads(CATALOGUE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    return payload.get("servers", [])


def availability(entry: dict) -> dict:
    """Can this server be started *here*, right now? The answer is evidence, not optimism.

    A `python3 -m <module>` server needs the module importable, not merely python3; an `npx` server
    needs npx and lets npm fetch the package; anything else needs the binary on PATH.
    """
    command = entry.get("command") or []
    binary = command[0] if command else ""
    found = shutil.which(binary) if binary else None
    why = ""
    if not command:
        why = "no launch command was verified for this server"
    elif not found:
        why = f"{binary} is not installed here"
    elif len(command) >= 3 and command[0].startswith("python") and command[1] == "-m":
        module = command[2]
        try:
            importable = importlib.util.find_spec(module) is not None
        except (ImportError, ValueError, ModuleNotFoundError):
            importable = False
        if not importable:
            why = f"python3 is present but the module {module} is not importable here"
    return {"name": entry.get("name"), "repository": entry.get("repository"),
            "command": " ".join(command), "available": (not why) and bool(found), "binary": found,
            "requires": entry.get("requires", ""), "tools": entry.get("tools", []),
            "verified": bool(entry.get("verified")), "why_not": why}


def connect(entries: list[dict] | None = None, cwd: str | None = None,
            limit: int = 6, timeout: float = HANDSHAKE_TIMEOUT) -> dict:
    """Binds every catalogued server that is installed. Returns bound tools and the refusals."""
    bound: dict[str, dict] = {}
    clients: dict[str, StdioClient] = {}
    skipped: list[dict] = []
    for entry in (entries if entries is not None else load_catalogue()):
        state = availability(entry)
        if not state["available"]:
            skipped.append({"name": entry.get("name"), "reason": state["why_not"]})
            continue
        if len(clients) >= limit:
            skipped.append({"name": entry.get("name"), "reason": f"binding limit of {limit} reached"})
            continue
        try:
            client = StdioClient(entry["command"], cwd=cwd)
            tools = client.tools()
        except (McpLinkError, OSError) as problem:
            skipped.append({"name": entry.get("name"), "reason": str(problem)[:200]})
            continue
        clients[entry["name"]] = client
        for tool in tools:
            name = f'mcp.{entry["name"]}.{tool["name"]}'
            bound[name] = {
                "fn": _make_caller(client, tool["name"]),
                "description": f'[MCP {entry["name"]}] {tool.get("description", "")}'[:400],
                "schema": tool.get("inputSchema") or {"type": "object", "properties": {}},
                "server": entry["name"],
            }
    return {"tools": bound, "clients": clients, "skipped": skipped}


def _make_caller(client: StdioClient, tool_name: str):
    def caller(**arguments):
        return client.call_tool(tool_name, arguments)
    return caller


def probe(timeout: float = HANDSHAKE_TIMEOUT) -> dict:
    catalogue = load_catalogue()
    states = [availability(entry) for entry in catalogue]
    installed = [state for state in states if state["available"]]
    results = []
    for state in installed[:4]:
        entry = next(e for e in catalogue if e.get("name") == state["name"])
        try:
            client = StdioClient(entry["command"])
            tools = [tool["name"] for tool in client.tools()]
            client.close()
            results.append({"name": state["name"], "tools": tools[:25], "count": len(tools)})
        except (McpLinkError, OSError) as problem:
            results.append({"name": state["name"], "error": str(problem)[:200]})
    return {"catalogue": len(catalogue), "installed": len(installed),
            "not_installed": len(states) - len(installed), "probed": results,
            "states": states}
