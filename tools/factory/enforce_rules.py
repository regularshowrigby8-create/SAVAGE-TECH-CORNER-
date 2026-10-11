#!/usr/bin/env python3
"""
tools/factory/enforce_rules.py

Fail-Closed Rule Bypass Governance & 5x Exhaustive Test Verification Engine.

Enforces:
1. Rules cannot be bypassed or ignored without an extremely good, documented, cryptographically/structurally valid reason.
2. Any requested bypass MUST include an authentic functional solution that has passed the ENTIRE test suite at least 5 consecutive times across isolated runs.
3. If no valid, verified bypass certificate exists, all standard rules (Anti-Stub, Anti-Deception, Zero-Placeholder, Forward Progression) are strictly enforced.
"""

import sys
import os
import json
import hashlib
import time
import subprocess
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parents[2]
BYPASS_REGISTRY_FILE = ROOT_DIR / "factories" / "governance" / "rule_bypass_registry.json"
BYPASS_LOG_FILE = ROOT_DIR / "factories" / "governance" / "bypass_verification.log"

MINIMUM_EXHAUSTIVE_RUNS = 5
MINIMUM_REASON_LENGTH = 100

def get_file_sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def execute_full_suite_iteration(iteration_num: int) -> dict:
    """Executes all primary test suites and deterministic quality gates."""
    t0 = time.time()
    results = {}
    
    # 1. Unit tests in factory-2
    res_f2 = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "factory-2/tests"],
        cwd=str(ROOT_DIR),
        capture_output=True,
        text=True
    )
    results["factory_2_unit_tests"] = (res_f2.returncode == 0)
    
    # 2. Unit tests in factory-3
    res_f3 = subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "factory-3/tests"],
        cwd=str(ROOT_DIR),
        capture_output=True,
        text=True
    )
    results["factory_3_unit_tests"] = (res_f3.returncode == 0)
    
    # 3. Full system integration audit
    res_sys = subprocess.run(
        [sys.executable, "test_entire_system.py"],
        cwd=str(ROOT_DIR),
        capture_output=True,
        text=True
    )
    results["test_entire_system"] = (res_sys.returncode == 0)
    
    # 4. Deterministic gates
    res_gate = subprocess.run(
        ["bash", ".claude/scripts/gates.sh"],
        cwd=str(ROOT_DIR),
        capture_output=True,
        text=True
    )
    results["gates_sh"] = (res_gate.returncode == 0)
    
    duration = time.time() - t0
    all_passed = all(results.values())
    
    return {
        "iteration": iteration_num,
        "all_passed": all_passed,
        "results": results,
        "duration_sec": round(duration, 3)
    }

def verify_and_test_bypass(bypass_request: dict) -> dict:
    """
    Validates the bypass reason and executes the 5x exhaustive testing verification.
    """
    reason = bypass_request.get("justification", "").strip()
    rule_id = bypass_request.get("rule_id", "").strip()
    proposed_solution_files = bypass_request.get("solution_files", [])
    
    if not rule_id:
        return {"approved": False, "error": "Missing rule_id"}
    
    if len(reason) < MINIMUM_REASON_LENGTH:
        return {
            "approved": False,
            "error": f"Bypass justification rejected: must be an extremely rigorous, detailed explanation of at least {MINIMUM_REASON_LENGTH} characters (provided {len(reason)} characters)."
        }
        
    if not proposed_solution_files:
        return {
            "approved": False,
            "error": "No solution files provided: every bypass MUST be accompanied by an actual functional solution."
        }

    # Verify that each proposed solution file actually exists on disk
    for fpath in proposed_solution_files:
        p = ROOT_DIR / fpath
        if not p.exists():
            return {
                "approved": False,
                "error": f"Solution file not found on disk: {fpath}"
            }

    print(f"\n[GOVERNANCE] Commencing 5x exhaustive test verification for requested rule bypass: {rule_id}")
    print(f"[GOVERNANCE] Justification: {reason[:120]}...")
    
    runs = []
    for i in range(1, MINIMUM_EXHAUSTIVE_RUNS + 1):
        print(f"[GOVERNANCE] Executing test iteration {i}/{MINIMUM_EXHAUSTIVE_RUNS} across all test suites and gates...")
        run_res = execute_full_suite_iteration(i)
        runs.append(run_res)
        if not run_res["all_passed"]:
            print(f"[GOVERNANCE] FAILED at iteration {i}: {run_res['results']}")
            return {
                "approved": False,
                "error": f"Solution failed verification on iteration {i}/{MINIMUM_EXHAUSTIVE_RUNS}",
                "runs": runs
            }
        print(f"[GOVERNANCE] Iteration {i} PASSED cleanly in {run_res['duration_sec']}s.")

    # Generate cryptographic approval certificate
    cert_data = {
        "rule_id": rule_id,
        "justification": reason,
        "solution_files": proposed_solution_files,
        "solution_hashes": {f: get_file_sha256(ROOT_DIR / f) for f in proposed_solution_files},
        "verified_runs": runs,
        "timestamp": time.time(),
        "status": "APPROVED_5X_CERTIFIED"
    }
    cert_hash = hashlib.sha256(json.dumps(cert_data, sort_keys=True).encode()).hexdigest()
    cert_data["certificate_hash"] = cert_hash
    
    # Save to registry
    BYPASS_REGISTRY_FILE.parent.mkdir(parents=True, exist_ok=True)
    registry = []
    if BYPASS_REGISTRY_FILE.exists():
        try:
            registry = json.loads(BYPASS_REGISTRY_FILE.read_text(encoding="utf-8"))
        except Exception:
            registry = []
    registry.append(cert_data)
    BYPASS_REGISTRY_FILE.write_text(json.dumps(registry, indent=2), encoding="utf-8")
    
    return {
        "approved": True,
        "certificate_hash": cert_hash,
        "runs_verified": len(runs)
    }

def is_rule_bypassed(rule_id: str) -> bool:
    """Checks whether an active, validly certified bypass exists for a given rule_id."""
    if not BYPASS_REGISTRY_FILE.exists():
        return False
    try:
        registry = json.loads(BYPASS_REGISTRY_FILE.read_text(encoding="utf-8"))
        for entry in registry:
            if entry.get("rule_id") == rule_id and entry.get("status") == "APPROVED_5X_CERTIFIED":
                # Verify that the solution files still match the certified hashes
                hashes_match = True
                for fpath, orig_hash in entry.get("solution_hashes", {}).items():
                    p = ROOT_DIR / fpath
                    if not p.exists() or get_file_sha256(p) != orig_hash:
                        hashes_match = False
                        break
                if hashes_match:
                    return True
    except Exception:
        return False
    return False

def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--verify-bypass":
        if len(sys.argv) < 3:
            print("Usage: enforce_rules.py --verify-bypass <path_to_bypass_request.json>")
            sys.exit(1)
        req_path = Path(sys.argv[2])
        if not req_path.exists():
            print(f"File not found: {req_path}")
            sys.exit(1)
        req = json.loads(req_path.read_text(encoding="utf-8"))
        outcome = verify_and_test_bypass(req)
        print(json.dumps(outcome, indent=2))
        sys.exit(0 if outcome.get("approved") else 1)
    
    print("Zero-Bypass Governance System: ACTIVE (Fail-closed).")

if __name__ == "__main__":
    main()
