#!/usr/bin/env python3
"""
JSON Schema & Model Contract Enforcement Validator.
Validates:
1. ErrorPacket schema contract between Middleman Broker (producer) and Factory 2 Agents (consumer).
2. ModelDiagnosis contract from MCP Model Server / Model Debugger.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Tuple

ROOT_DIR = Path(__file__).resolve().parent.parent
SCHEMAS_DIR = ROOT_DIR / "schemas"


def validate_error_packet(packet: Dict[str, Any]) -> Tuple[bool, List[str]]:
    errors: List[str] = []
    required = ["packet_id", "timestamp", "target", "findings", "retry_count", "max_retries", "status"]
    for field in required:
        if field not in packet:
            errors.append(f"Missing required field: '{field}'")

    if "packet_id" in packet and not re.match(r"^ERR_PKT_[0-9]{8}_[0-9]{6}_[a-zA-Z0-9_-]+$", str(packet["packet_id"])):
        errors.append(f"Invalid packet_id format: '{packet['packet_id']}'")

    if "findings" in packet:
        if not isinstance(packet["findings"], list):
            errors.append("Field 'findings' must be a list")
        else:
            for idx, finding in enumerate(packet["findings"]):
                if not isinstance(finding, dict):
                    errors.append(f"Finding [{idx}] must be an object")
                    continue
                for req in ["severity", "tool", "description"]:
                    if req not in finding:
                        errors.append(f"Finding [{idx}] missing required property '{req}'")
                if finding.get("severity") not in {"CRITICAL", "ERROR", "WARNING", "INFO"}:
                    errors.append(f"Finding [{idx}] has invalid severity '{finding.get('severity')}'")

    if "retry_count" in packet and (not isinstance(packet["retry_count"], int) or packet["retry_count"] < 0):
        errors.append("Field 'retry_count' must be a non-negative integer")

    if "max_retries" in packet and (not isinstance(packet["max_retries"], int) or packet["max_retries"] < 1):
        errors.append("Field 'max_retries' must be an integer >= 1")

    return len(errors) == 0, errors


def validate_model_diagnosis(diagnosis: Dict[str, Any]) -> Tuple[bool, List[str]]:
    errors: List[str] = []
    required = ["status", "model", "engine", "remediations", "confidence"]
    for field in required:
        if field not in diagnosis:
            errors.append(f"Missing required field: '{field}'")

    if "remediations" in diagnosis and not isinstance(diagnosis["remediations"], list):
        errors.append("Field 'remediations' must be a list of strings")

    if "confidence" in diagnosis:
        conf = diagnosis["confidence"]
        if not isinstance(conf, (int, float)) or not (0.0 <= conf <= 1.0):
            errors.append("Field 'confidence' must be a float between 0.0 and 1.0")

    return len(errors) == 0, errors


if __name__ == "__main__":
    test_packet = {
        "packet_id": "ERR_PKT_20261011_040000_test123",
        "timestamp": "2026-10-11T04:00:00Z",
        "target": "shell/android_project",
        "findings": [{"severity": "ERROR", "tool": "APKAnalyzer", "description": "Invalid signature"}],
        "retry_count": 0,
        "max_retries": 3,
        "status": "DISPATCHED",
    }
    valid, errs = validate_error_packet(test_packet)
    assert valid, f"Validation failed: {errs}"
    print("[CONTRACT-VALIDATOR] Self-test verified cleanly.")
