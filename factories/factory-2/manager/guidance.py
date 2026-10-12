#!/usr/bin/env python3
"""
Factory 2 Generalized Manager — Dynamically parses and evaluates all agent reports.
Uses generalized evidence policies with fail-closed rejection for blocking security
and syntax defects. Semantics:
1. The full agent panel must have reported (missing report = REJECTED).
2. A report that cannot be parsed is UNREADABLE — never an implicit pass.
3. Each producer has its own blocking policy aligned with its exit criteria, so
   the manager never ACCEPTS a report whose producer would have failed the job.
"""

from __future__ import annotations

import ast
import os
from pathlib import Path
from typing import Any, Dict, List

# Per-producer blocking policy: finding types that must REJECT the pipeline.
BLOCKING_BY_AGENT: Dict[str, set] = {
    "correctness": {"syntax_error", "unreachable_code", "dead_code_function", "incomplete_marker"},
    "security": {
        "hardcoded_token",
        "high_entropy_secret",
        "insecure_cleartext_traffic",
        "insecure_ssl_bypass",
        "insecure_file_access",
        "insecure_universal_file_access",
        "insecure_subprocess_shell",
    },
    "architecture": {"missing_category_directory", "layer_leak", "parse_error"},
    "quality": {"missing_quality_infrastructure", "empty_source_file"},
    "model_debugger": {"insufficient_models", "model_catalog_error", "proposal_verification_error"},
}


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
    expected_reports = set(BLOCKING_BY_AGENT.keys()) | {"critic"}
    present_reports = {p.stem for p in reports_dir.glob("*_report.md")}
    missing_reports = {f"{name}_report" for name in expected_reports} - present_reports
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

        # Check for explicit critic rejection (including Verdict: FIX-FIRST)
        if "Verdict: FAIL" in content or "Verdict: FIX-FIRST" in content or ("BLOCKING" in content and "Zero blocking" not in content):
            has_fatal_blocker = True
            manager_notes.append(f"Agent {agent_name}: REJECTED — explicit blocking verdict in report.")
            continue

        # Extract structured findings report; anything unparseable is escalated.
        findings: List[Any] = []
        if "Findings:" in content:
            raw = content.split("Findings:", 1)[1].strip()
            try:
                val = ast.literal_eval(raw)
                if isinstance(val, list):
                    findings = val
                else:
                    manager_notes.append(f"Agent {agent_name}: MALFORMED REPORT (Findings is not a list) — ESCALATE.")
                    has_fatal_blocker = True
                    continue
            except Exception:
                manager_notes.append(f"Agent {agent_name}: MALFORMED REPORT (unparseable Findings) — ESCALATE.")
                has_fatal_blocker = True
                continue
        elif agent_name == "critic":
            # Critic reports verdicts instead of findings; reached only when not rejected above.
            if "Verdict: SHIP" in content:
                manager_notes.append("Agent critic: ACCEPTED — Verdict SHIP with zero blocking findings.")
            else:
                manager_notes.append("Agent critic: MALFORMED REPORT (no recognized verdict) — ESCALATE.")
                has_fatal_blocker = True
            continue
        else:
            manager_notes.append(f"Agent {agent_name}: MALFORMED REPORT (no Findings section) — ESCALATE.")
            has_fatal_blocker = True
            continue

        # Generalized Decision Engine with per-producer blocking policy
        if agent_name not in BLOCKING_BY_AGENT:
            manager_notes.append(f"Agent {agent_name}: UNKNOWN PRODUCER with report — ESCALATE (fail-closed).")
            has_fatal_blocker = True
            continue

        blocking_types = BLOCKING_BY_AGENT[agent_name]
        blocking_findings = [f for f in findings if isinstance(f, dict) and f.get("type") in blocking_types]
        advisory_findings = [f for f in findings if f not in blocking_findings]
        if blocking_findings:
            has_fatal_blocker = True
            types_seen = sorted({f.get("type") for f in blocking_findings})
            manager_notes.append(
                f"Agent {agent_name}: REJECTED (BLOCKING) — {len(blocking_findings)} blocking finding(s) {types_seen}. Immediate repair required."
            )
        elif advisory_findings:
            manager_notes.append(
                f"Agent {agent_name}: ACCEPTED (ADVISORY) — {len(advisory_findings)} non-blocking finding(s) noted for future cycles."
            )
        elif "Verdict: SHIP" in content:
            manager_notes.append(f"Agent {agent_name}: ACCEPTED — Clean report verified. Zero blocking findings.")
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
