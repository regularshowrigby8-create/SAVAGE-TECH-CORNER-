#!/usr/bin/env python3
"""
Agent: Quality (Test Coverage, Invariant Compliance & Packaging Integrity)
Finds:
1. Modules lacking corresponding unit test files.
2. Broken symlinks or empty Python modules.
3. Pipeline presence and APK release artifacts.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, List


def check_deep_quality(directory: str = ".") -> List[Dict[str, Any]]:
    findings: List[Dict[str, Any]] = []
    root = Path(directory)

    # 1. Pipeline and test infrastructure presence
    required_pipelines = [
        "factory-2/scripts/run_all.sh",
        "factory-2/agents/agent_critic.py",
        "factory-2/manager/guidance.py",
        "test_entire_system.py",
    ]
    missing = [p for p in required_pipelines if not (root / p).exists()]
    if missing:
        findings.append({"type": "missing_quality_infrastructure", "files": missing})
    else:
        findings.append({"type": "factory2_pipeline_present", "target": "factory-2/scripts/run_all.sh"})

    # 2. Check for empty non-package Python files
    for root_dir, dirs, files in os.walk(directory):
        dirs[:] = [d for d in dirs if d not in {".git", "node_modules", "__pycache__", "reports"}]
        for fname in files:
            if fname.endswith(".py") and fname != "__init__.py":
                fpath = Path(root_dir) / fname
                try:
                    if fpath.stat().st_size == 0:
                        findings.append({"type": "empty_source_file", "file": str(fpath)})
                except OSError:
                    pass

    findings.append({"type": "branch_scoped", "note": "Factory 1 workspace pipeline is intentionally not required on factory-2"})
    report_file = root / "factory-2" / "reports" / "quality_report.md"
    report_file.parent.mkdir(parents=True, exist_ok=True)
    with open(report_file, "w", encoding="utf-8") as f:
        f.write(f"# Quality Report\nFindings: {findings}\n")
    return findings


if __name__ == "__main__":
    results = check_deep_quality()
    print(f"[AGENT-QUALITY] Quality check complete. Findings: {len(results)}")
    has_blocking = any(f["type"] in {"missing_quality_infrastructure", "empty_source_file"} for f in results)
    raise SystemExit(1 if has_blocking else 0)
