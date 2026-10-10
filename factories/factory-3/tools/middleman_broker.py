"""Factory 3 Middleman Feedback Broker.

Inspects code with Factory 3 Android debugging tools.
If defects or warnings are detected, it bundles them into an ErrorPacket
with rich diagnostic context and sends it straight back to Factory 2 AI agents.
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

    def audit_target(self, target_dir: Path | str) -> Dict[str, Any]:
        """Runs the entire suite of verified and unverified Android debugging tools."""
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

        has_blocking = any(f.get("severity") in {"CRITICAL", "ERROR"} for f in findings)

        report = {
            "target": str(target_path),
            "clean": not has_blocking,
            "total_findings": len(findings),
            "findings": findings,
            "apk_metrics": apk_res,
            "experimental_diagnostics": exp_res,
        }

        # If issues exist, package ErrorPacket and send straight to Factory 2 AI agents!
        if has_blocking:
            packet = self.dispatch_error_packet(target_path, findings)
            report["error_packet"] = packet
            report["status"] = "RETURNED_TO_AGENTS_FOR_REMEDATION"
        else:
            report["status"] = "PASSED_FACTORY_3_CERTIFICATION"

        return report

    def dispatch_error_packet(self, target_path: Path, findings: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Creates a structured ErrorPacket and delivers it to Factory 2 AI agents' inbox."""
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
        packet_id = f"ERR_PKT_{timestamp}_{target_path.stem}"

        # Generate model insight context
        insight_lines = [
            f"=== FACTORY 3 DEBUGGING REPORT: {packet_id} ===",
            f"Target Codebase: {target_path}",
            f"Total issues identified by Android tools: {len(findings)}",
            "",
            "Diagnostic Findings:",
        ]
        for idx, f in enumerate(findings, 1):
            insight_lines.append(
                f"{idx}. [{f.get('tool')}] {f.get('category')} ({f.get('severity')}) at {f.get('file')}:{f.get('line')}\n"
                f"   Message: {f.get('message')}\n"
                f"   Context snippet: {f.get('context')}\n"
                f"   Remediation advice: {f.get('remediation')}"
            )

        packet: Dict[str, Any] = {
            "packet_id": packet_id,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "target_dir": str(target_path),
            "findings_count": len(findings),
            "findings": findings,
            "agent_instruction": (
                "Please review the attached diagnostic context. Formulate a non-mutating debug proposal "
                "in factory-2/debug/proposals/ resolving these Android tool issues without modifying files directly."
            ),
            "model_insight_prompt": "\n".join(insight_lines),
        }

        # Save to Factory 2 AI Agents' inbox
        packet_file = self.inbox_dir / f"{packet_id}.json"
        with open(packet_file, "w", encoding="utf-8") as f:
            json.dump(packet, f, indent=2)

        print(f"[MIDDLEMAN] Dispatched ErrorPacket '{packet_id}' with {len(findings)} finding(s) to Factory 2 Agents.")
        return packet


if __name__ == "__main__":
    broker = MiddlemanBroker()
    target = sys.argv[1] if len(sys.argv) > 1 else "factories/final-product"
    result = broker.audit_target(target)
    print(f"Audit Status: {result['status']} (Findings: {result['total_findings']})")
