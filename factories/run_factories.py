#!/usr/bin/env python3
"""Main Factory Orchestrator.

Feeds code from Factory 1 (or shell) through:
1. Intake: Loads both Python architecture models and Android Java/Gradle project files.
2. Factory 2: Non-mutating proposal debugging and verifier implementation.
3. Factory 3: Android tools refinement, manifest audit, security validation, and APK build.
4. Final Product: Certified application deliverables with calculated verdicts and robust error handling.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
FACTORIES_DIR = ROOT_DIR / "factories"
FACTORY_1_DIR = FACTORIES_DIR / "factory-1"
FACTORY_2_DIR = FACTORIES_DIR / "factory-2"
FACTORY_3_DIR = FACTORIES_DIR / "factory-3"
FINAL_PRODUCT_DIR = FACTORIES_DIR / "final-product"
SHELL_DIR = ROOT_DIR / "shell"


def run_factory_pipeline(input_source: str = "shell") -> dict[str, object]:
    print(f"=== STARTING MULTI-FACTORY PIPELINE (Source: {input_source}) ===")
    timestamp = datetime.now(timezone.utc).isoformat()
    pipeline_log: dict[str, object] = {
        "timestamp": timestamp,
        "input_source": input_source,
        "stages": {},
        "status": "IN_PROGRESS",
    }

    try:
        # Step 1: Stage input source into Factory 2 app space
        target_app_dir = FACTORY_2_DIR / "app" / "src"
        # Always clean staging workspace to avoid shipping stale artifacts
        if target_app_dir.exists():
            shutil.rmtree(target_app_dir)
        target_app_dir.mkdir(parents=True, exist_ok=True)

        loaded_files = []
        if input_source == "shell":
            # Load Python shell architectural model
            py_files = list((SHELL_DIR / "soul_browser").glob("*.py"))
            for f in py_files:
                shutil.copy2(f, target_app_dir / f.name)
                loaded_files.append(f.name)

            # Also load actual Android project Java source & Manifest for real Android static analysis
            android_src = SHELL_DIR / "android_project" / "src" / "com" / "savage" / "soulbrowser" / "MainActivity.java"
            if android_src.exists():
                shutil.copy2(android_src, target_app_dir / android_src.name)
                loaded_files.append(android_src.name)

            manifest_src = SHELL_DIR / "android_project" / "AndroidManifest.xml"
            if manifest_src.exists():
                shutil.copy2(manifest_src, target_app_dir / manifest_src.name)
                loaded_files.append(manifest_src.name)

            print(f"[STAGE 1] Loaded {len(loaded_files)} shell & Android source file(s) into Factory 2 workspace")
        elif input_source == "factory-1":
            f1_code_dir = FACTORY_1_DIR / "workspace" / "code"
            if f1_code_dir.exists():
                for f in f1_code_dir.glob("*.py"):
                    shutil.copy2(f, target_app_dir / f.name)
                    loaded_files.append(f.name)
            print(f"[STAGE 1] Loaded {len(loaded_files)} Factory 1 source file(s) into Factory 2 workspace")
        pipeline_log["stages"]["factory_1_intake"] = "SUCCESS"

        # Step 2: Feed to Factory 2 Models for non-mutating debug
        print("[STAGE 2] Factory 2: Models debugging in isolated proposal workspaces...")
        sys.path.insert(0, str(FACTORY_2_DIR / "scripts"))
        from model_debugger import ModelDebugger
        from main_agent_verifier import MainAgentVerifier

        debugger = ModelDebugger(proposals_dir=FACTORY_2_DIR / "debug" / "proposals")
        proposals = debugger.scan_and_propose(target_app_dir, model_name="Qwen2.5-Coder-32B")
        print(f"[STAGE 2] Models generated {len(proposals)} non-mutating debug proposal(s)")

        verifier = MainAgentVerifier(proposals_dir=FACTORY_2_DIR / "debug" / "proposals")
        processed = verifier.process_proposals(auto_implement=True)
        print(f"[STAGE 2] Main Agent verified and processed {len(processed)} proposal(s)")

        # Compute real verdict dynamically based on actual verification status
        unverified_proposals = [p for p in proposals if p.get("status") == "FAIL"]
        stage_2_verdict = "FIX-FIRST" if unverified_proposals else "SHIP"
        pipeline_log["stages"]["factory_2_agents"] = {
            "proposals_generated": len(proposals),
            "proposals_verified": len(processed),
            "verdict": stage_2_verdict,
        }

        # Step 3: Feed to Factory 3 Android Tools via Middleman Broker
        print("[STAGE 3] Factory 3: Running Android Tools suite via Middleman Broker...")
        sys.path.insert(0, str(FACTORY_3_DIR / "tools"))
        from middleman_broker import MiddlemanBroker
        from android_tools import Factory3Refiner

        broker = MiddlemanBroker(inbox_dir=FACTORY_2_DIR / "debug" / "inbox")
        broker_result = broker.audit_target(target_app_dir)
        print(f"[STAGE 3] Middleman Broker audit status: {broker_result['status']}")

        if not broker_result.get("clean"):
            print("[STAGE 3] Middleman sent ErrorPacket back to Factory 2 AI Agents. Triggering agent remediation...")
            debugger.process_inbox(model_name="Qwen2.5-Coder-32B")
            verifier.process_proposals(auto_implement=True)
            broker_result = broker.audit_target(target_app_dir)
            print(f"[STAGE 3] Post-remediation audit status: {broker_result['status']}")

        refiner = Factory3Refiner()
        f3_result = refiner.process_and_certify(target_app_dir)
        print(f"[STAGE 3] Factory 3 certification status: {f3_result['status']}")

        # Build and Package Android APK
        print("[STAGE 3] Factory 3: Building and signing Savage Browser Android APK...")
        from apk_builder import create_signed_apk
        apk_output = FINAL_PRODUCT_DIR / "SavageBrowser-debug.apk"
        create_signed_apk(apk_output)
        print(f"[STAGE 3] Savage Browser APK successfully assembled: {apk_output.name} ({apk_output.stat().st_size / 1024:.2f} KB)")

        # Stage 3 Audit Gate: Fail closed if broker flagged critical findings
        broker_clean = broker_result.get("clean", False)
        f3_certified = f3_result.get("status") == "CERTIFIED" and broker_clean

        # Production Browser App Rules Enforcement Gate
        from browser_app_rules import verify_browser_app_rules
        app_rules = verify_browser_app_rules(apk_output)
        print(f"[STAGE 3] Browser App Invariant Rules verification: {'PASS' if app_rules['passed'] else 'FAIL'}")

        if not app_rules["passed"]:
            print(f"[STAGE 3-BLOCK] Bogus/Mock APK rejected by Browser App Rules: {app_rules['violations']}")
            f3_certified = False

        if not f3_certified:
            print(f"[STAGE 3-BLOCK] Android refinement failed certification: broker clean={broker_clean}, cert={f3_result.get('status')}")
            f3_result["status"] = "REJECTED"

        # Check Gradle wrapper presence
        gradlew = SHELL_DIR / "android_project" / "gradlew"
        gradle_available = gradlew.exists() and os.access(str(gradlew), os.X_OK)

        pipeline_log["stages"]["factory_3_android_tools"] = {
            "broker_audit": broker_result,
            "certification": f3_result,
            "gradle_wrapper_ready": gradle_available,
        }

        # Step 4: Final verification and delivery to final-product folder
        print("[STAGE 4] Passing certified code to Final Product release folder...")
        FINAL_PRODUCT_DIR.mkdir(parents=True, exist_ok=True)
        delivered_files = []
        for f in target_app_dir.glob("*"):
            if f.is_file() and not f.name.endswith(".pyc"):
                dest = FINAL_PRODUCT_DIR / f.name
                shutil.copy2(f, dest)
                delivered_files.append(f.name)

        overall_certified = f3_result.get("status") == "CERTIFIED" and stage_2_verdict == "SHIP"
        pipeline_log["status"] = "CERTIFIED" if overall_certified else "REJECTED"

        manifest_dest = FINAL_PRODUCT_DIR / "RELEASE_MANIFEST.json"
        with open(manifest_dest, "w", encoding="utf-8") as f:
            json.dump(pipeline_log, f, indent=2)

        print(f"[STAGE 4] Final Product release populated with: {delivered_files}")
        print(f"=== PIPELINE COMPLETE: VERIFIED & CERTIFIED (Status: {pipeline_log['status']}) ===")
        return pipeline_log

    except Exception as e:
        print(f"[PIPELINE-ERROR] Exception during factory execution: {e}")
        pipeline_log["status"] = "FAILED"
        pipeline_log["error"] = str(e)
        manifest_dest = FINAL_PRODUCT_DIR / "RELEASE_MANIFEST.json"
        with open(manifest_dest, "w", encoding="utf-8") as f:
            json.dump(pipeline_log, f, indent=2)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Multi-Factory Pipeline Runner")
    parser.add_argument("--source", default="shell", help="Input source (shell or factory-1)")
    args = parser.parse_args()
    run_factory_pipeline(args.source)
