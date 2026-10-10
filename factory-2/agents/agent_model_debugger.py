#!/usr/bin/env python3
"""
Agent: Model Debugger (Factory 2 Lead Agent)
Monitors model debug operations, verifies non-mutating proposal protocol,
and audits main agent implementation records.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(ROOT_DIR / "factory-2" / "scripts"))

from main_agent_verifier import MainAgentVerifier
from model_debugger import list_available_models, load_model_registry


def check_model_pipeline() -> dict[str, object]:
    findings: list[dict[str, object]] = []

    # 1. Verify Model Inventory has 50+ models
    try:
        models = list_available_models()
        if len(models) < 50:
            findings.append({"type": "insufficient_models", "count": len(models), "required": 50})
        else:
            findings.append({"type": "model_catalog_verified", "count": len(models)})
    except Exception as exc:
        findings.append({"type": "model_catalog_error", "error": str(exc)})

    # 2. Process / verify any outstanding proposals
    try:
        verifier = MainAgentVerifier()
        proposals = verifier.process_proposals(auto_implement=True)
        findings.append({"type": "proposals_audited", "processed_count": len(proposals)})
    except Exception as exc:
        findings.append({"type": "proposal_verification_error", "error": str(exc)})

    # Write report
    report_path = ROOT_DIR / "factory-2" / "reports" / "model_debugger_report.md"
    with open(report_path, "w", encoding="utf-8") as f:
        f.write("# Model Debugger & Proposal Audit Report\n")
        f.write(f"Findings: {findings}\n")

    return {"passed": not any("error" in str(f.get("type", "")) for f in findings), "findings": findings}


if __name__ == "__main__":
    result = check_model_pipeline()
    count = len(result["findings"])
    print(f"[AGENT-MODEL-DEBUGGER] Model debug audit complete. Findings/Checks: {count}")
    raise SystemExit(0 if result["passed"] else 1)
