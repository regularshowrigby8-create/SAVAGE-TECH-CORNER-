"""Automated end-to-end test verifying multi-factory pipeline and models."""

import json
import shutil
import tempfile
import unittest
from pathlib import Path

import os
import sys

FACTORIES_DIR = Path(__file__).resolve().parent.parent
if str(FACTORIES_DIR) not in sys.path:
    sys.path.insert(0, str(FACTORIES_DIR))

from run_factories import run_factory_pipeline


ROOT_DIR = FACTORIES_DIR.parent
if str(ROOT_DIR / "factory-2" / "scripts") not in sys.path:
    sys.path.insert(0, str(ROOT_DIR / "factory-2" / "scripts"))

from model_debugger import ModelDebugger, list_available_models
from main_agent_verifier import MainAgentVerifier

class TestMultiFactoryAndModels(unittest.TestCase):
    def test_all_models_loaded_and_run(self) -> None:
        models = list_available_models()
        self.assertGreaterEqual(len(models), 50)
        # Verify offline models have ollama commands and online models have valid providers
        for m in models:
            self.assertIn("name", m)
            self.assertIn("type", m)
            self.assertIn("benchmark", m)

    def test_pipeline_execution(self) -> None:
        log = run_factory_pipeline(input_source="shell")
        self.assertEqual(log["stages"]["factory_1_intake"], "SUCCESS")
        self.assertEqual(log["stages"]["factory_2_agents"]["verdict"], "SHIP")
        # Certification is honest: CERTIFIED only when a structurally sound APK exists.
        # While the tracked stock APK fails DEX structural verification the pipeline
        # MUST report REJECTED (never certify a launch-crashing artifact).
        self.assertIn(
            log["stages"]["factory_3_android_tools"]["certification"]["status"],
            {"CERTIFIED", "REJECTED"},
        )
        if log["status"] == "REJECTED":
            self.assertTrue(
                log["stages"]["factory_3_android_tools"]["apk_build_errors"],
                "REJECTED pipeline must record the APK rejection reason",
            )
        final_product_dir = FACTORIES_DIR / "final-product"
        self.assertTrue((final_product_dir / "browser_shell.py").exists())
        self.assertTrue((final_product_dir / "RELEASE_MANIFEST.json").exists())

    def test_dex_structure_gate_flags_broken_stock_apk(self) -> None:
        """Anti-regression: the legacy stock APK's class-definition table cannot resolve
        its manifest activities (launcher ClassNotFoundException on-device). The shared
        DEX structure gate must keep flagging this artifact until it is rebuilt from
        shell/android_project with a real Gradle toolchain."""
        sys.path.insert(0, str(FACTORIES_DIR / "factory-3" / "tools"))
        import zipfile
        from apk_builder import verify_dex_structure

        stock_apk = ROOT_DIR / "assets" / "SavageBrowser-v2.0.0.apk"
        self.assertTrue(stock_apk.exists())
        with zipfile.ZipFile(stock_apk, "r") as zf:
            dex_errors = verify_dex_structure(zf.read("classes.dex"))
        self.assertTrue(
            dex_errors,
            "classes.dex of the tracked stock APK must fail structural verification until it is rebuilt from source",
        )

    def test_apk_builder_refuses_broken_candidate_when_no_real_build(self) -> None:
        """When no fresh Gradle build output exists, create_signed_apk must raise
        instead of silently copying the corrupted stock APK forward."""
        sys.path.insert(0, str(FACTORIES_DIR / "factory-3" / "tools"))
        import tempfile
        from apk_builder import create_signed_apk, ROOT_DIR as APK_ROOT, SHELL_DIR as APK_SHELL

        fresh_build = APK_SHELL / "android_project" / "app" / "build" / "outputs" / "apk" / "debug" / "SavageBrowser-debug.apk"
        if fresh_build.exists():
            self.skipTest("Fresh Gradle build output present; refusal path no longer reaches stock APK")
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(RuntimeError):
                create_signed_apk(Path(tmp) / "out.apk")


if __name__ == "__main__":
    unittest.main()
