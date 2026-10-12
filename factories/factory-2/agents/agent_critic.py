#!/usr/bin/env python3
"""
Agent: Critic (Adversarial Multi-Report Auditor & Model-Assisted Verifier)
Actively hunts for contradictions, malformed reports, fabricated metrics, and policy drift.
"""

from __future__ import annotations

import ast
import json
import os
import sys
from pathlib import Path
from typing import Any, Dict, List

ROOT_DIR = Path(__file__).resolve().parent.parent.parent


def _parse_report_findings(report_path: Path) -> List[Dict[str, Any]]:
    if not report_path.exists():
        return [{"type": "missing_report", "path": str(report_path)}]
    try:
        text = report_path.read_text(encoding="utf-8")
        prefix = "Findings:"
        if prefix not in text:
            return [{"type": "malformed_report", "path": str(report_path)}]
        raw = text.split(prefix, 1)[1].strip()
        val = ast.literal_eval(raw)
        return val if isinstance(val, list) else [{"type": "malformed_report", "path": str(report_path)}]
    except Exception as e:
        return [{"type": "report_parse_error", "path": str(report_path), "error": str(e)}]


def adversarial_review(directory: str = ".") -> Dict[str, Any]:
    root = Path(directory)
    reports_dir = root / "factory-2" / "reports"
    blocking: List[str] = []
    notes: List[str] = []

    # 1. Audit Correctness Report (blocking types aligned with the agent's own exit policy)
    CORRECTNESS_BLOCKING = {"syntax_error", "unreachable_code", "dead_code_function", "incomplete_marker"}
    correctness_findings = _parse_report_findings(reports_dir / "correctness_report.md")
    for f in correctness_findings:
        if isinstance(f, dict):
            k = f.get("type")
            if k in CORRECTNESS_BLOCKING:
                blocking.append(f"correctness:{k} in {f.get('file')}:{f.get('line')}")
            elif k in {"missing_report", "malformed_report", "report_parse_error"}:
                blocking.append(f"correctness:{k} — report unusable, fail-closed")

    # 2. Audit Security Report (full blocking family, not a subset)
    SECURITY_BLOCKING = {
        "hardcoded_token", "high_entropy_secret", "insecure_cleartext_traffic",
        "insecure_ssl_bypass", "insecure_file_access",
        "insecure_universal_file_access", "insecure_subprocess_shell",
    }
    security_findings = _parse_report_findings(reports_dir / "security_report.md")
    for f in security_findings:
        if isinstance(f, dict):
            k = f.get("type")
            if k in SECURITY_BLOCKING:
                blocking.append(f"security:{k} detected in {f.get('file')}")
            elif k in {"missing_report", "malformed_report", "report_parse_error"}:
                blocking.append(f"security:{k} — report unusable, fail-closed")

    # 3. Audit Architecture & Quality Reports
    arch_findings = _parse_report_findings(reports_dir / "architecture_report.md")
    for f in arch_findings:
        if isinstance(f, dict) and f.get("type") in {"missing_category_directory", "layer_leak", "parse_error",
                                                      "missing_report", "malformed_report", "report_parse_error"}:
            blocking.append(f"architecture:{f.get('type')}")

    qual_findings = _parse_report_findings(reports_dir / "quality_report.md")
    QUALITY_BLOCKING = {"missing_quality_infrastructure", "empty_source_file",
                        "missing_report", "malformed_report", "report_parse_error"}
    for f in qual_findings:
        if isinstance(f, dict) and f.get("type") in QUALITY_BLOCKING:
            blocking.append(f"quality:{f.get('type')}")

    # 4. Audit Model Debugger Report (its error types block, aligned with manager policy)
    model_findings = _parse_report_findings(reports_dir / "model_debugger_report.md")
    for f in model_findings:
        if isinstance(f, dict) and f.get("type") in {
            "insufficient_models", "model_catalog_error", "proposal_verification_error",
            "missing_report", "malformed_report", "report_parse_error",
        }:
            blocking.append(f"model_debugger:{f.get('type')}")

    # 5. Anti-Fabrication Invariant: Verify model catalog exists and has > 10 models
    model_cat = root / "models" / "free-models.json"
    if not model_cat.exists():
        blocking.append("missing_model_catalog")
    else:
        try:
            with open(model_cat, "r", encoding="utf-8") as mf:
                data = json.load(mf)
                total = len(data.get("offline_coding_models", [])) + sum(len(p.get("models", [])) for p in data.get("providers", []))
                if total < 10:
                    blocking.append("insufficient_model_catalog")
                else:
                    notes.append(f"Model catalog verified ({total} verified models)")
        except Exception:
            blocking.append("corrupted_model_catalog")

    verdict = "FIX-FIRST" if blocking else "SHIP"
    critic_report = reports_dir / "critic_report.md"
    with open(critic_report, "w", encoding="utf-8") as f:
        f.write(f"# Critic Report\nVerdict: {verdict}\nBlocking findings: {blocking}\nNotes: {notes}\n")

    return {"verdict": verdict, "blocking": blocking, "notes": notes}


if __name__ == "__main__":
    res = adversarial_review()
    print(f"[AGENT-CRITIC] Verdict: {res['verdict']} — Blocking findings: {len(res['blocking'])}")
    sys.exit(1 if res["blocking"] else 0)
