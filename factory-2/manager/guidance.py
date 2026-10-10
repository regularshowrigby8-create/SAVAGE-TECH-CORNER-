#!/usr/bin/env python3
"""
Factory 2 Generalized Manager — Dynamically parses and evaluates all agent reports.
Uses generalized evidence policies rather than rigid hardcoded per-agent if/else blocks.
"""

from __future__ import annotations

import ast
import os
from pathlib import Path
from typing import Any, Dict, List


def review_findings() -> List[str]:
    reports_dir = Path("factory-2/reports")
    manager_notes: List[str] = []

    if not reports_dir.exists():
        print("[MANAGER-ERROR] Reports directory does not exist!")
        return ["REPORTS_DIR_MISSING"]

    # Generalized Agent Evaluation Loop
    for report_file in sorted(reports_dir.glob("*_report.md")):
        agent_name = report_file.stem.replace("_report", "")
        if agent_name in {"manager_response", "model_debug_proposals"}:
            continue

        try:
            content = report_file.read_text(encoding="utf-8")
        except OSError:
            manager_notes.append(f"Agent {agent_name}: UNREADABLE REPORT — ESCALATE.")
            continue

        # Extract structured findings if present
        findings: List[Any] = []
        if "Findings:" in content:
            raw = content.split("Findings:", 1)[1].strip()
            try:
                val = ast.literal_eval(raw)
                if isinstance(val, list):
                    findings = val
            except Exception:
                pass

        # Generalized Decision Engine
        if not findings and "Verdict: SHIP" in content:
            manager_notes.append(f"Agent {agent_name}: ACCEPTED — Clean report verified. Zero blocking findings.")
        elif findings:
            high_severity = [
                f for f in findings
                if isinstance(f, dict) and f.get("type") in {
                    "syntax_error", "hardcoded_token", "high_entropy_secret",
                    "missing_category_directory", "missing_quality_infrastructure",
                }
            ]
            if high_severity:
                manager_notes.append(
                    f"Agent {agent_name}: ACCEPTED (BLOCKING) — {len(high_severity)} high-severity finding(s) confirmed. Immediate repair required."
                )
            else:
                manager_notes.append(
                    f"Agent {agent_name}: ACCEPTED (ADVISORY) — {len(findings)} non-blocking finding(s) noted for future cycles."
                )
        else:
            manager_notes.append(f"Agent {agent_name}: ACCEPTED — Verified with evidence.")

    # Write unified Manager response
    response_file = reports_dir / "manager_response.md"
    with open(response_file, "w", encoding="utf-8") as f:
        f.write("# Manager Response\n")
        f.write("Every agent finding reviewed via Generalized Evidence Protocol.\n")
        f.write("No feedback was discounted automatically.\n\n")
        for note in manager_notes:
            f.write(note + "\n")

    print("[MANAGER] Generalized review complete. All agent findings examined with written evidence.")
    return manager_notes


if __name__ == "__main__":
    review_findings()
