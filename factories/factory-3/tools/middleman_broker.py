"""Factory 3 Middleman Feedback Broker.

Inspects code with Factory 3 Android debugging tools.
If defects or warnings are detected, it bundles them into an ErrorPacket
with rich diagnostic context and sends it straight back to Factory 2 AI agents.
Tracks retry state within the packet payload to enforce hard escalation limits.
"""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

ROOT_DIR = Path(__file__).resolve().parent.parent.parent.parent
FACTORY_2_INBOX = ROOT_DIR / "factory-2" / "debug" / "inbox"
FACTORY_2_INBOX.mkdir(parents=True, exist_ok=True)

try:
    from android_tools import AndroidManifestAuditor, AndroidResourceRefiner
    from debugging_toolkit import (
        Abi16KbPageSizeChecker,
        APKAnalyzer,
        InteractionAssertionValidator,
        LeakCanaryDetector,
        StrictModeDetector,
        UnverifiedExperimentalTools,
    )
    from stitched_code_auditor import StitchedCodeAuditor
except ImportError:
    from .android_tools import AndroidManifestAuditor, AndroidResourceRefiner
    from .debugging_toolkit import (
        Abi16KbPageSizeChecker,
        APKAnalyzer,
        InteractionAssertionValidator,
        LeakCanaryDetector,
        StrictModeDetector,
        UnverifiedExperimentalTools,
    )
    from .stitched_code_auditor import StitchedCodeAuditor


class MiddlemanBroker:
    """Mediates between Factory 3 Android debugging tools and Factory 2 AI agents."""

    def __init__(self, inbox_dir: Optional[Path] = None) -> None:
        self.inbox_dir = inbox_dir or FACTORY_2_INBOX
        self.inbox_dir.mkdir(parents=True, exist_ok=True)

        self.manifest_auditor = AndroidManifestAuditor()
        self.resource_refiner = AndroidResourceRefiner()
        self.apk_analyzer = APKAnalyzer()
        self.leak_detector = LeakCanaryDetector()
        self.strict_mode = StrictModeDetector()
        self.abi_checker = Abi16KbPageSizeChecker()
        self.interaction_validator = InteractionAssertionValidator()
        self.experimental_tools = UnverifiedExperimentalTools()
        self.stitched_auditor = StitchedCodeAuditor()

    def audit_target(self, target_dir: Path | str, retry_count: int = 0, max_retries: int = 3) -> Dict[str, Any]:
        """Runs the entire suite of verified and unverified Android debugging tools with loop guardrails."""
        target_path = Path(target_dir).resolve()
        findings: List[Dict[str, Any]] = []

        # 1. APK & Resource Analysis
        apk_res = self.apk_analyzer.analyze(target_path)

        # 2. Source file scans for Memory Leaks and StrictMode violations
        for f in target_path.rglob("*.py"):
            findings.extend(self.leak_detector.scan_file(f))
            findings.extend(self.strict_mode.scan_file(f))

        # 3. Native ABI 16KB alignment check
        abi_res = self.abi_checker.check_native_config(target_path)
        findings.extend(abi_res.get("findings", []))

        # 4. Interaction & dead button assertions
        findings.extend(self.interaction_validator.validate(target_path))

        # 5. Experimental unverified tools
        exp_res = self.experimental_tools.speculative_bytecode_analysis(target_path)

        # 6. Stitched Codebase & Multi-Language boundary audit
        stitched_res = self.stitched_auditor.audit_codebase(target_path)
        findings.extend(stitched_res.get("violations", []))

        has_blocking = any(f.get("severity") in {"CRITICAL", "ERROR"} for f in findings)

        report: Dict[str, Any] = {
            "target": str(target_path),
            "clean": not has_blocking,
            "total_findings": len(findings),
            "findings": findings,
            "apk_metrics": apk_res,
            "experimental_diagnostics": exp_res,
            "retry_count": retry_count,
            "max_retries": max_retries,
        }

        # Guardrail: Check retry ceiling
        if has_blocking:
            if retry_count >= max_retries:
                report["status"] = "ESCALATED_HUMAN_INTERVENTION_REQUIRED"
                report["guardrail_triggered"] = True
                print(f"[MIDDLEMAN-GUARDRAIL] Maximum retries ({max_retries}) reached for '{target_path.name}'. Escalating to human review.")
            else:
                next_retry = retry_count + 1
                packet = self.dispatch_error_packet(target_path, findings, retry_count=next_retry)
                report["error_packet"] = packet
                report["status"] = "RETURNED_TO_AGENTS_FOR_REMEDATION"
                report["next_retry_count"] = next_retry
        else:
            report["status"] = "PASSED_FACTORY_3_CERTIFICATION"

        return report

    def dispatch_error_packet(self, target_path: Path, findings: List[Dict[str, Any]], retry_count: int = 1) -> Dict[str, Any]:
        """Creates a structured ErrorPacket and delivers it to Factory 2 AI agents' inbox with incremented retry count."""
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
        packet_id = f"ERR_PKT_{timestamp}_{target_path.stem}"
        packet: Dict[str, Any] = {
            "packet_id": packet_id,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "origin": "Factory-3.MiddlemanBroker",
            "target": str(target_path),
            "retry_count": retry_count,
            "findings_count": len(findings),
            "findings": findings,
            "model_insight_prompt": f"Defects detected in {target_path.name}: " + "; ".join(f.get("message", "") for f in findings),
            "remediation_request": "Factory 2 agents please analyze findings, generate non-mutating proposals, and submit to verifier.",
        }

        packet_path = self.inbox_dir / f"{packet_id}.json"
        with open(packet_path, "w", encoding="utf-8") as f:
            json.dump(packet, f, indent=2)

        print(f"[MIDDLEMAN] Dispatched ErrorPacket '{packet_id}' (attempt {retry_count}) with {len(findings)} finding(s) to Factory 2 Agents.")
        return packet
