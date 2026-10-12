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


def self_test() -> bool:
    """Positive AND negative self-tests: a validator that cannot reject an
    invalid packet would be a rubber stamp, so both directions are proven."""
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
    if not valid:
        print(f"[CONTRACT-VALIDATOR: FAIL] Known-good packet rejected: {errs}")
        return False

    bad_packet = dict(test_packet, retry_count=-1, findings=[{"severity": "BOGUS", "tool": "x"}])
    bad_valid, _bad_errs = validate_error_packet(bad_packet)
    if bad_valid:
        print("[CONTRACT-VALIDATOR: FAIL] Invalid packet was accepted — validator is a rubber stamp.")
        return False

    bad_diagnosis = {"status": "ok", "model": "m", "engine": "e", "remediations": "not-a-list", "confidence": 1.7}
    diag_valid, _diag_errs = validate_model_diagnosis(bad_diagnosis)
    if diag_valid:
        print("[CONTRACT-VALIDATOR: FAIL] Invalid diagnosis was accepted — validator is a rubber stamp.")
        return False

    print("[CONTRACT-VALIDATOR] Self-test verified cleanly (accepts valid, rejects invalid).")
    return True


def validate_real_packets() -> tuple[int, int]:
    """Validate every real serialized ErrorPacket found in the repo."""
    checked = 0
    invalid = 0
    for base in ("factory-2", "factory-3", "factories"):
        base_path = Path(base)
        if not base_path.exists():
            continue
        for jf in base_path.rglob("*.json"):
            if "node_modules" in jf.parts or "__pycache__" in jf.parts:
                continue
            try:
                data = json.loads(jf.read_text(encoding="utf-8", errors="ignore"))
            except (json.JSONDecodeError, OSError):
                continue
            if isinstance(data, dict) and "packet_id" in data:
                checked += 1
                ok, errors = validate_error_packet(data)
                if not ok:
                    invalid += 1
                    print(f"[CONTRACT-VALIDATOR: FAIL] Real packet {jf} is invalid: {errors}")
    return checked, invalid


if __name__ == "__main__":
    ok = self_test()
    checked, invalid = validate_real_packets()
    print(f"[CONTRACT-VALIDATOR] Real packets validated: {checked} checked, {invalid} invalid.")
    if not ok or invalid:
        sys.exit(1)
    sys.exit(0)
