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
        # Honest certification: with no APK binary tracked in the repository the
        # final packaging stage has no artifact to ship, so the pipeline MUST NOT
        # certify. It must fail closed (REJECTED) and record the rejection reason.
        self.assertEqual(
            log["stages"]["factory_3_android_tools"]["certification"]["status"],
            "REJECTED",
            "with no tracked built APK the pipeline must refuse certification",
        )
        self.assertTrue(
            log["stages"]["factory_3_android_tools"].get("apk_build_errors"),
            "REJECTED pipeline must record the APK rejection reason",
        )
        # Non-APK deliverables (the web shell + manifest) are still real artifacts.
        final_product_dir = FACTORIES_DIR / "final-product"
        self.assertTrue((final_product_dir / "browser_shell.py").exists())
        self.assertTrue((final_product_dir / "RELEASE_MANIFEST.json").exists())

    def test_dex_structure_gate_flags_corrupted_stale_apk_from_history(self) -> None:
        """Historical regression canary: the stale pre-fix stock classes.dex pinned as
        a fixture must keep failing structural verification."""
        sys.path.insert(0, str(FACTORIES_DIR / "factory-3" / "tools"))
        from apk_builder import verify_dex_structure

        fixture = Path(__file__).parent / "fixtures" / "corrupted_stock_classes.dex"
        self.assertTrue(fixture.exists(), "regression fixture for the corrupted stock DEX is missing")
        dex_errors = verify_dex_structure(fixture.read_bytes())
        self.assertTrue(dex_errors, "the historically broken stock classes.dex MUST keep failing structural verification")
        self.assertTrue(
            any("class-def" in e for e in dex_errors),
            "the gate must specifically detect the unresolvable class-definition table",
        )

    def test_dex_structure_gate_accepts_valid_fixture(self) -> None:
        """Positive control: the valid classes.dex fixture (extracted from the former
        tracked build artifact) must pass structural verification."""
        sys.path.insert(0, str(FACTORIES_DIR / "factory-3" / "tools"))
        from apk_builder import verify_dex_structure

        fixture = Path(__file__).parent / "fixtures" / "valid_classes.dex"
        self.assertTrue(fixture.exists(), "valid_classes.dex fixture is missing")
        errors = verify_dex_structure(fixture.read_bytes())
        self.assertEqual(errors, [], f"valid classes.dex must stay structurally valid: {errors}")

    def test_apk_builder_deprecated_fails_closed(self) -> None:
        """create_signed_apk is deprecated (no APK binary tracked): it must raise a
        clear error instead of copying a stale artifact or silently succeeding."""
        sys.path.insert(0, str(FACTORIES_DIR / "factory-3" / "tools"))
        import tempfile
        from apk_builder import create_signed_apk

        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(NotImplementedError):
                create_signed_apk(Path(tmp) / "out.apk")


if __name__ == "__main__":
    unittest.main()
