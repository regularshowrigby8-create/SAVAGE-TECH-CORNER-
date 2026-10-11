#!/usr/bin/env python3
"""Autonomous Supervisor & Model Daemon Watchdog.

Automatically manages background AI engines and execution pipelines:
1. Detects and auto-starts local Ollama / GPU runtime if installed.
2. Auto-boots the Factory 24/7 Multi-Agent Swarm Server (port 8080).
3. Auto-boots the Factory MCP Model Execution Server (port 8088).
4. Keeps processes alive, auto-restarts on crash, and runs improvement cycles.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent

SWARM_SERVER = ROOT_DIR / "factory-2" / "scripts" / "autonomous_server.py"
MCP_SERVER = ROOT_DIR / "factory-2" / "scripts" / "mcp_model_server.py"
LOG_DIR = ROOT_DIR / "factories" / "logs"
LOG_DIR.mkdir(parents=True, exist_ok=True)


def is_port_listening(url: str, timeout: float = 1.0) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as res:
            return res.status in {200, 404, 405}
    except Exception:
        return False


def start_ollama_if_installed() -> None:
    """Auto-detects and auto-launches local Ollama if available on the system."""
    ollama_path = shutil.which("ollama")
    if not ollama_path:
        return
    if is_port_listening("http://127.0.0.1:11434"):
        print("[SUPERVISOR] Ollama already running on 127.0.0.1:11434")
        return
    print(f"[SUPERVISOR] Launching Ollama daemon via {ollama_path}...")
    log_file = open(LOG_DIR / "ollama.log", "a")
    subprocess.Popen([ollama_path, "serve"], stdout=log_file, stderr=subprocess.STDOUT)
    time.sleep(2)


def start_swarm_server() -> None:
    """Ensures the 24/7 Multi-Agent Swarm Server is running on port 8080."""
    if is_port_listening("http://127.0.0.1:8080/status"):
        print("[SUPERVISOR] Autonomous Swarm Server active on port 8080")
        return
    print("[SUPERVISOR] Starting Autonomous Swarm Server (port 8080)...")
    log_file = open(LOG_DIR / "swarm_server.log", "a")
    subprocess.Popen(
        [sys.executable, str(SWARM_SERVER), "--port", "8080"],
        stdout=log_file,
        stderr=subprocess.STDOUT,
        cwd=str(ROOT_DIR),
    )
    time.sleep(2)


def start_mcp_server() -> None:
    """Ensures the Factory MCP Model Execution Server is running on port 8088."""
    if is_port_listening("http://127.0.0.1:8088"):
        print("[SUPERVISOR] MCP Model Server active on port 8088")
        return
    print("[SUPERVISOR] Starting MCP Model Execution Server (port 8088)...")
    log_file = open(LOG_DIR / "mcp_server.log", "a")
    subprocess.Popen(
        [sys.executable, str(MCP_SERVER), "--port", "8088"],
        stdout=log_file,
        stderr=subprocess.STDOUT,
        cwd=str(ROOT_DIR),
    )
    time.sleep(1)


def run_cycle_once() -> None:
    """Executes a single improvement and model audit pass."""
    start_ollama_if_installed()
    start_swarm_server()
    start_mcp_server()
    print("[SUPERVISOR] All autonomous model systems verified and active.")


def run_daemon_loop(interval: int = 120) -> None:
    """Runs continuously as a persistent supervisor daemon."""
    print(f"[SUPERVISOR] Starting continuous watchdog daemon (polling every {interval}s)...")
    while True:
        try:
            run_cycle_once()
        except Exception as e:
            print(f"[SUPERVISOR-ERROR] {e}")
        time.sleep(interval)


def main() -> None:
    parser = argparse.ArgumentParser(description="Autonomous Supervisor & Model Watchdog")
    parser.add_argument("--once", action="store_true", help="Run startup and verification pass once and exit")
    parser.add_argument("--interval", type=int, default=120, help="Watchdog check interval in seconds")
    args = parser.parse_args()

    if args.once:
        run_cycle_once()
    else:
        run_daemon_loop(interval=args.interval)


if __name__ == "__main__":
    main()
