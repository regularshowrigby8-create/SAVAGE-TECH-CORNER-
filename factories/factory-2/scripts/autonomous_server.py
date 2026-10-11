"""Autonomous Factory Daemon & Dual-Engine Server (24/7).

Hosts on port 8080:
1. Continuous Model Finder Engine: Scans GitHub & HuggingFace / open registries for free coding LLMs.
2. Continuous Multi-Agent Autonomous Optimizer: Runs 24/7 autonomous improvement cycles powered
   by DeepSeek-R1-Distill-70B (HumanEval 94.5%) with a multi-agent swarm:
   - Agent-Architecture
   - Agent-Security
   - Agent-Quality
   - Agent-Performance
   - Agent-Synthesis
3. Real-Time Telemetry Dashboard: Exposes JSON and HTML status endpoints for 24/7 observation.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any, Dict

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
if not (ROOT_DIR / "factories").exists() and (ROOT_DIR.parent / "factories").exists():
    ROOT_DIR = ROOT_DIR.parent

sys.path.insert(0, str(ROOT_DIR / "factory-2" / "scripts"))
from model_finder import ModelFinder
from autonomous_optimizer import MultiAgentOptimizer


class DaemonState:
    """Shared state between background worker threads and the HTTP server."""

    def __init__(self) -> None:
        self.started_at = datetime.now(timezone.utc).isoformat()
        self.last_model_scan: str = "Never"
        self.models_cataloged: int = 0
        self.last_improvement_cycle: str = "Never"
        self.improvement_cycles_completed: int = 0
        self.active_llm: str = "DeepSeek-R1-Distill-70B (94.5% HumanEval)"
        self.agents_active: list[str] = [
            "Agent-Architecture",
            "Agent-Security",
            "Agent-Quality",
            "Agent-Performance",
            "Agent-Synthesis",
        ]
        self.latest_status: Dict[str, Any] = {}
        self.lock = threading.Lock()


STATE = DaemonState()


def run_model_finder_loop(interval: int = 300) -> None:
    """Continuously finds new free coding models in the background."""
    finder = ModelFinder()
    while True:
        try:
            candidates = finder.scan_for_models()
            added = finder.integrate_discoveries(candidates)
            with STATE.lock:
                STATE.last_model_scan = datetime.now(timezone.utc).isoformat()
                STATE.models_cataloged = len(finder.load_catalog().get("offline_coding_models", []))
            print(f"[DAEMON-FINDER] Scan completed: {len(candidates)} discovered, {added} integrated.")
        except Exception as e:
            print(f"[DAEMON-FINDER-ERROR] {e}")
        time.sleep(interval)


def run_autonomous_optimizer_loop(interval: int = 180) -> None:
    """Continuously executes multi-agent improvements on the factory 24/7."""
    optimizer = MultiAgentOptimizer(ROOT_DIR)
    while True:
        try:
            summary = optimizer.run_improvement_cycle()
            with STATE.lock:
                STATE.last_improvement_cycle = summary["timestamp"]
                STATE.improvement_cycles_completed = summary["cycle"]
                STATE.latest_status = summary
        except Exception as e:
            print(f"[DAEMON-OPTIMIZER-ERROR] {e}")
        time.sleep(interval)


class ServerHandler(BaseHTTPRequestHandler):
    """Exposes status and control endpoints."""

    def do_GET(self) -> None:
        if self.path in {"/", "/status", "/api/status"}:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            with STATE.lock:
                payload = {
                    "daemon": "SAVAGE-FACTORY-AUTONOMOUS-SERVER",
                    "mode": "24/7 Autonomous Continuous Operation",
                    "assigned_llm": STATE.active_llm,
                    "active_agents": STATE.agents_active,
                    "started_at": STATE.started_at,
                    "last_model_scan": STATE.last_model_scan,
                    "models_cataloged": STATE.models_cataloged,
                    "last_improvement_cycle": STATE.last_improvement_cycle,
                    "cycles_completed": STATE.improvement_cycles_completed,
                    "latest_audit": STATE.latest_status,
                }
            self.wfile.write(json.dumps(payload, indent=2).encode("utf-8"))
        elif self.path == "/health":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"OK")
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format: str, *args: Any) -> None:
        return None
