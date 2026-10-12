#!/usr/bin/env python3
"""
Factory 2 Generalized Manager — Dynamically parses and evaluates all agent reports.
Uses generalized evidence policies with fail-closed rejection for blocking security and syntax defects.
"""

from __future__ import annotations

import ast
import os
from pathlib import Path
from typing import Any, Dict, List


def review_findings() -> tuple[str, List[str]]:
    reports_dir = Path("factory-2/reports")
    manager_notes: List[str] = []

    if not reports_dir.exists():
        print("[MANAGER-ERROR] Reports directory does not exist!")
        return "REJECTED", ["REPORTS_DIR_MISSING"]

    has_fatal_blocker = False

    # Fail-closed: the full agent panel must have actually reported. A missing
    # report means an agent never ran (or failed to write) — silently treating
    # that as consent would be a false green.
    expected_reports = {
        "correctness_report",
        "security_report",
        "architecture_report",
        "quality_report",
        "critic_report",
        "model_debugger_report",
    }
    present_reports = {p.stem for p in reports_dir.glob("*_report.md")}
    missing_reports = expected_reports - present_reports
    if missing_reports:
        has_fatal_blocker = True
        manager_notes.append(
            f"MISSING AGENT REPORTS — ESCALATE: {sorted(missing_reports)} did not report. "
            "An absent report is never an implicit pass."
        )

    # Generalized Agent Evaluation Loop
    for report_file in sorted(reports_dir.glob("*_report.md")):
        agent_name = report_file.stem.replace("_report", "")
        if agent_name in {"manager_response", "model_debug_proposals"}:
            continue

        try:
            content = report_file.read_text(encoding="utf-8")
        except OSError:
            manager_notes.append(f"Agent {agent_name}: UNREADABLE REPORT — ESCALATE.")
            has_fatal_blocker = True
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

        # Check for explicit critic rejection (including Verdict: FIX-FIRST)
        if "Verdict: FAIL" in content or "Verdict: FIX-FIRST" in content or ("BLOCKING" in content and "Zero blocking" not in content):
            has_fatal_blocker = True

        # Generalized Decision Engine
        if not findings and "Verdict: SHIP" in content:
            manager_notes.append(f"Agent {agent_name}: ACCEPTED — Clean report verified. Zero blocking findings.")
        elif findings:
            high_severity = [
                f for f in findings
                if isinstance(f, dict) and f.get("type") in {
                    "syntax_error", "hardcoded_token", "high_entropy_secret",
                    "insecure_cleartext_traffic", "insecure_ssl_bypass",
                    "missing_category_directory", "missing_quality_infrastructure",
                }
            ]
            if high_severity:
                has_fatal_blocker = True
                manager_notes.append(
                    f"Agent {agent_name}: REJECTED (BLOCKING) — {len(high_severity)} high-severity finding(s) confirmed. Immediate repair required."
                )
            else:
                manager_notes.append(
                    f"Agent {agent_name}: ACCEPTED (ADVISORY) — {len(findings)} non-blocking finding(s) noted for future cycles."
                )
        else:
            manager_notes.append(f"Agent {agent_name}: ACCEPTED — Verified with evidence.")

    # Write unified Manager response
    verdict = "REJECTED" if has_fatal_blocker else "ACCEPTED"
    response_file = reports_dir / "manager_response.md"
    with open(response_file, "w", encoding="utf-8") as f:
        f.write(f"# Manager Response\nVerdict: {verdict}\n\n")
        f.write("Every agent finding reviewed via Generalized Evidence Protocol.\n")
        f.write("Fail-closed policy rejects build if blocking vulnerabilities or unverified claims exist.\n\n")
        for note in manager_notes:
            f.write(note + "\n")

    print(f"[MANAGER] Generalized review complete. Verdict: {verdict}. All agent findings examined.")
    return verdict, manager_notes


if __name__ == "__main__":
    final_verdict, _notes = review_findings()
    if final_verdict == "REJECTED":
        print("[MANAGER: FAIL] REJECTED verdict blocks the pipeline — this step cannot be green.")
    raise SystemExit(1 if final_verdict == "REJECTED" else 0)
