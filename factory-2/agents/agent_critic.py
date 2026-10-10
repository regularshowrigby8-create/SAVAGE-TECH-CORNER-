#!/usr/bin/env python3
"""Adversarial Factory 2 critic: SHIP only when generated evidence is clean."""
import ast
import os


def _load_findings(path):
    if not os.path.exists(path):
        return []
    text = open(path, encoding="utf-8").read()
    prefix = "Findings:"
    if prefix not in text:
        return [{"type": "malformed_report", "path": path}]
    try:
        value = ast.literal_eval(text.split(prefix, 1)[1].strip())
        return value if isinstance(value, list) else [{"type": "malformed_report", "path": path}]
    except (SyntaxError, ValueError):
        return [{"type": "malformed_report", "path": path}]


def adversarial_review(directory="."):
    reports_dir = "factory-2/reports"
    notes = []
    blocking = []
    for report_name in ("quality_report.md", "architecture_report.md"):
        findings = _load_findings(os.path.join(reports_dir, report_name))
        for finding in findings:
            kind = finding.get("type") if isinstance(finding, dict) else "unknown"
            if kind not in {"factory2_pipeline_present", "branch_scoped"}:
                blocking.append(f"{report_name}: {kind}")
                notes.append(f"{report_name}: {kind} — requires fix")
    model_path = "models/free-models.json"
    if not os.path.exists(model_path):
        blocking.append("missing_model_config")
        notes.append("Free model config missing — requires setup")
    else:
        notes.append("Free model config present")
    for required in ("factory-2/apk-analysis/apk_scanner.py", "factory-2/manager/guidance.py"):
        if not os.path.exists(required):
            blocking.append(f"missing:{required}")
            notes.append(f"Missing required module: {required}")
    verdict = "FIX-FIRST" if blocking else "SHIP"
    with open(f"{reports_dir}/critic_report.md", "w", encoding="utf-8") as f:
        f.write(f"# Critic Report\nVerdict: {verdict}\nBlocking findings: {blocking}\nNotes: {notes}\n")
    return {"verdict": verdict, "blocking": blocking, "notes": notes}


if __name__ == "__main__":
    result = adversarial_review()
    print(f"[AGENT-CRITIC] Verdict: {result['verdict']} — Blocking findings: {len(result['blocking'])}")
    raise SystemExit(1 if result["blocking"] else 0)
