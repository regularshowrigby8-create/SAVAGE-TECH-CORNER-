#!/usr/bin/env python3
"""Temporary end-to-end stress test and feedback audit of the entire factory system.

Tests:
1. Model Catalog (Offline & Online 50+ models)
2. Soul Browser Shell (WebView, Bridge, Navigation, Buttons)
3. Factory 1 Workspace & Deterministic Scanners
4. Factory 2 Multi-Agent Execution & Criticism Stack
5. Factory 2 Non-mutating Model Debugging & Main Agent Proposal Verifier
6. Factory 3 Android Tools, LeakCanary, StrictMode, 16KB ABI, and Middleman Loop
7. End-to-End Multi-Factory Pipeline to Final Product
8. Canonical Release Archive Integrity (factory-setup.zip)
9. Quality Gates (.claude/scripts/gates.sh)
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import unittest
import zipfile
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent

sys.path.insert(0, str(ROOT_DIR / "factories"))
sys.path.insert(0, str(ROOT_DIR / "factories" / "factory-2" / "scripts"))
sys.path.insert(0, str(ROOT_DIR / "factories" / "factory-3" / "tools"))
sys.path.insert(0, str(ROOT_DIR / "shell"))


class FullSystemAudit(unittest.TestCase):
    def test_01_model_catalog(self) -> None:
        catalog_path = ROOT_DIR / "models" / "free-models.json"
        self.assertTrue(catalog_path.exists())
        with open(catalog_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        total_online = sum(len(p.get("models", [])) for p in data.get("providers", []))
        total_offline = len(data.get("offline_coding_models", []))
        total = total_online + total_offline
        print(f"\n[AUDIT] Model Catalog: {total} total ({total_offline} offline, {total_online} online)")
        self.assertGreaterEqual(total, 50)

    def test_02_soul_browser_shell(self) -> None:
        from soul_browser.browser_shell import SoulBrowserShell
        shell = SoulBrowserShell()
        res = shell.webview.load_url("https://savage-tech-corner.io")
        self.assertEqual(res["status"], "loaded")
        self.assertEqual(shell.bridge.invoke("getAppVersion"), "1.0.0-soul")
        for btn in ["btn_back", "btn_forward", "btn_refresh", "btn_home", "btn_menu", "btn_tabs"]:
            out = shell.click_button(btn)
            self.assertIsInstance(out, dict)
        print("[AUDIT] Soul Browser Shell: PASS")

    def test_03_factory_1_scanners(self) -> None:
        sys.path.insert(0, str(ROOT_DIR / "workspace" / "code"))
        from debug_pipeline import run_debug_pipeline
        res = run_debug_pipeline(ROOT_DIR / "workspace" / "code")
        self.assertTrue(res["passed"])
        print("[AUDIT] Workspace Scanners: PASS")

    def test_04_factory_2_run_all(self) -> None:
        cmd = ["bash", "factory-2/scripts/run_all.sh"]
        proc = subprocess.run(cmd, cwd=ROOT_DIR, capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, f"run_all.sh failed: {proc.stderr}\n{proc.stdout}")
        print("[AUDIT] Factory 2 run_all.sh: PASS")

    def test_05_factory_3_middleman_feedback_loop(self) -> None:
        from middleman_broker import MiddlemanBroker
        broker = MiddlemanBroker(inbox_dir=ROOT_DIR / "factory-2" / "debug" / "inbox")
        audit = broker.audit_target(ROOT_DIR / "factories" / "final-product")
        self.assertTrue(audit.get("clean"))
        self.assertEqual(audit["status"], "PASSED_FACTORY_3_CERTIFICATION")
        print("[AUDIT] Factory 3 Middleman Broker & Android Tools: PASS")

    def test_06_master_orchestrator(self) -> None:
        from run_factories import run_factory_pipeline
        result = run_factory_pipeline(input_source="shell")
        self.assertEqual(result["stages"]["factory_1_intake"], "SUCCESS")
        self.assertEqual(result["stages"]["factory_2_agents"]["verdict"], "SHIP")
        self.assertTrue((ROOT_DIR / "factories" / "final-product" / "browser_shell.py").exists())
        print("[AUDIT] Master Pipeline Orchestrator: PASS")

    def test_07_zip_archive_integrity(self) -> None:
        zip_path = ROOT_DIR / "factories" / "factory-setup.zip"
        self.assertTrue(zip_path.exists())
        with zipfile.ZipFile(zip_path, "r") as zf:
            names = zf.namelist()
            for prefix in ["factory-2/", "factory-3/", "final-product/"]:
                self.assertTrue(any(n.startswith(prefix) for n in names), f"Missing {prefix} in zip")
        print("[AUDIT] Canonical Zip Archive: PASS")

    def test_08_deterministic_gates(self) -> None:
        proc = subprocess.run([sys.executable, 'tools/anti_deception_auditor.py'], cwd=ROOT_DIR, capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0)
        self.assertIn('Repository passed all authenticity', proc.stdout)
        print('[AUDIT] Deterministic Anti-Deception Audit: PASS')

        gate_proc = subprocess.run(['bash', '.claude/scripts/gates.sh', '--subshell'], cwd=ROOT_DIR, capture_output=True, text=True)
        self.assertEqual(gate_proc.returncode, 0)
        self.assertIn('VERDICT: PASS', gate_proc.stdout)
        print('[AUDIT] Master Deterministic Gates: PASS')



if __name__ == "__main__":
    # Without this entry point the module body only *defined* the audit: `python3
    # test_entire_system.py` exited 0 without running a single test, which made GATE 11 of
    # .claude/scripts/gates.sh vacuous. The audit now really executes; it invokes
    # `gates.sh --subshell` internally, so there is no recursion.
    unittest.main(verbosity=2)
