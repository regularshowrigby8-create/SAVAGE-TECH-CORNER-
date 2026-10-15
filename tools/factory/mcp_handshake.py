#!/usr/bin/env python3
"""Runs a real MCP handshake against candidate servers and prints what each one answered.

Not part of the component: this is the harness used to move a catalogue entry from "catalogued" to
"verified", by starting the server for real, sending `initialize` and asking for its tool list. A
server that never answers is recorded as unverified with the reason it gave, never as verified.

    python3 tools/factory/mcp_handshake.py --candidates tools/factory/mcp_candidates.json \
        --output /tmp/handshake2.json
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "sealed-factories" / "runtime"))

import mcplink  # noqa: E402


def diagnose(entry: dict, seconds: float = 10.0) -> str:
    """Runs the command once more with its stderr kept, to name why it would not answer.

    The client discards a server's stderr because a working run must not be polluted by banners;
    when the server exits instead of answering, that discarded output is the only thing that says
    why (a missing API key, a missing runtime, a wrong argument), so it is captured here.
    """
    import subprocess
    try:
        done = subprocess.run([str(part) for part in entry["command"]], input="",
                              capture_output=True, text=True, timeout=seconds,
                              cwd=entry.get("cwd"))
    except subprocess.TimeoutExpired as slow:
        text = (slow.stderr or b"").decode("utf-8", "replace") if isinstance(slow.stderr, bytes) \
            else (slow.stderr or "")
        return "still running (no answer inside the handshake budget): " + text.strip()[-400:]
    except OSError as problem:
        return f"could not start: {problem}"
    text = (done.stderr or done.stdout or "").strip()
    return f"exit={done.returncode}: " + (text[-400:] if text else "no output")


def handshake(entry: dict, timeout: int) -> dict:
    name = entry["name"]
    command = entry.get("command") or []
    env = None
    if entry.get("env"):
        import os
        env = dict(os.environ)
        env.update(entry["env"])
    started = time.time()
    try:
        client = mcplink.StdioClient([str(part) for part in command], cwd=entry.get("cwd"), env=env)
    except Exception as problem:  # noqa: BLE001 - every way of failing is a result here
        return {"name": name, "command": command, "verified": False,
                "seconds": round(time.time() - started, 1),
                "error": f"{type(problem).__name__}: {problem}",
                "diagnosis": diagnose(entry)}
    try:
        tools = client.tools()
        return {"name": name, "command": command, "verified": True,
                "seconds": round(time.time() - started, 1),
                "server": client.server_info,
                "tools": [tool.get("name") for tool in tools],
                "count": len(tools),
                "schemas": {tool.get("name"): tool.get("inputSchema", {}) for tool in tools}}
    except Exception as problem:  # noqa: BLE001
        return {"name": name, "command": command, "verified": False,
                "seconds": round(time.time() - started, 1),
                "server": client.server_info,
                "error": f"{type(problem).__name__}: {problem}",
                "diagnosis": diagnose(entry)}
    finally:
        try:
            client.close()
        except Exception:  # noqa: BLE001 - closing a dead process is not a result
            pass


def main() -> int:
    parser = argparse.ArgumentParser(description="Real MCP handshakes against candidate servers")
    parser.add_argument("--candidates", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--only", default="", help="comma-separated names to run")
    parser.add_argument("--timeout", type=int, default=45)
    args = parser.parse_args()

    # A first-run npx/uvx install is slower than a started server, so the client's handshake budget
    # is what --timeout sets. The component's own default stays as it is: this is the harness.
    mcplink.HANDSHAKE_TIMEOUT = args.timeout
    candidates = json.loads(Path(args.candidates).read_text(encoding="utf-8"))
    wanted = [name for name in (args.only or "").split(",") if name]
    results = []
    for entry in candidates:
        if wanted and entry["name"] not in wanted:
            continue
        print(f"--- {entry['name']}: {' '.join(str(p) for p in entry.get('command') or [])}",
              flush=True)
        result = handshake(entry, args.timeout)
        marker = "OK" if result["verified"] else "NO"
        detail = (f"{result['count']} tools: {', '.join((result.get('tools') or [])[:6])}"
                  if result["verified"] else result.get("error", "")[:160])
        print(f"    [{marker}] {result['seconds']}s {detail}", flush=True)
        results.append(result)
        Path(args.output).write_text(json.dumps(results, indent=1), encoding="utf-8")
    verified = [r for r in results if r["verified"]]
    print(f"\n{len(verified)}/{len(results)} verified by handshake; written to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
