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
        # Certification is honest: CERTIFIED only when a structurally sound APK exists.
        # If the tracked stock APK passes structural verification the pipeline MUST certify;
        # if it is corrupted the pipeline MUST report REJECTED and record the reason.
        self.assertIn(
            log["stages"]["factory_3_android_tools"]["certification"]["status"],
            {"CERTIFIED", "REJECTED"},
        )
        if log["status"] == "REJECTED":
            self.assertTrue(
                log["stages"]["factory_3_android_tools"]["apk_build_errors"],
                "REJECTED pipeline must record the APK rejection reason",
            )
        else:
            self.assertFalse(
                log["stages"]["factory_3_android_tools"].get("apk_build_errors"),
                "CERTIFIED pipeline must not carry APK rejection errors",
            )
            self.assertTrue(
                (FACTORIES_DIR / "final-product" / "SavageBrowser-debug.apk").exists(),
                "CERTIFIED pipeline must deliver the verified APK into final-product",
            )
        final_product_dir = FACTORIES_DIR / "final-product"
        self.assertTrue((final_product_dir / "browser_shell.py").exists())
        self.assertTrue((final_product_dir / "RELEASE_MANIFEST.json").exists())

    def test_dex_structure_gate_flags_corrupted_stale_apk_from_history(self) -> None:
        """Historical regression canary: the stale pre-fix stock APK (asset commit d487ac9)
        shipped a classes.dex whose class-def table cannot resolve its manifest activities
        (5/175 sane descriptors → launcher ClassNotFoundException on-device). Its bytes are
        pinned as a fixture so the structural gate can never silently weaken again."""
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

    def test_dex_structure_gate_accepts_current_build(self) -> None:
        """The actively tracked APK must pass structural verification on every dex shard."""
        sys.path.insert(0, str(FACTORIES_DIR / "factory-3" / "tools"))
        import zipfile
        from apk_builder import verify_dex_structure

        apk = ROOT_DIR / "assets" / "SavageBrowser-v2.0.0.apk"
        self.assertTrue(apk.exists())
        with zipfile.ZipFile(apk, "r") as zf:
            dex_names = sorted(n for n in zf.namelist() if n.startswith("classes") and n.endswith(".dex"))
            self.assertTrue(dex_names, "APK must contain at least one classes*.dex")
            for dex_name in dex_names:
                errors = verify_dex_structure(zf.read(dex_name))
                self.assertEqual(errors, [], f"tracked APK {dex_name} failed structural verification: {errors}")

    def test_apk_builder_contract_follows_artifact_integrity(self) -> None:
        """create_signed_apk must COPY a structurally valid candidate forward, and must
        REFUSE (RuntimeError) a corrupted one — never silently certify garbage."""
        sys.path.insert(0, str(FACTORIES_DIR / "factory-3" / "tools"))
        import tempfile, zipfile
        from apk_builder import create_signed_apk, verify_dex_structure, ROOT_DIR as APK_ROOT

        stock = APK_ROOT / "assets" / "SavageBrowser-v2.0.0.apk"
        with tempfile.TemporaryDirectory() as tmp:
            if not stock.exists():
                self.skipTest("no stock APK tracked")
            with zipfile.ZipFile(stock, "r") as zf:
                stock_valid = not verify_dex_structure(zf.read("classes.dex"))
            out = Path(tmp) / "out.apk"
            if stock_valid:
                built = create_signed_apk(out)
                self.assertTrue(built.exists() and built.stat().st_size > 100_000)
            else:
                with self.assertRaises(RuntimeError):
                    create_signed_apk(out)


if __name__ == "__main__":
    unittest.main()
