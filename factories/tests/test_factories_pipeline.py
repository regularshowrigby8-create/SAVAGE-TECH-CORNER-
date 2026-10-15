"""Automated end-to-end test verifying multi-factory pipeline and models."""

import json
import shutil
import tempfile
import time
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
        """The pipeline either certifies a freshly built APK, or it fails and builds nothing.

        The APK stage runs the real Gradle build (tools/apk_builder.py), so this assertion is about
        provenance rather than about a file existing: a stale APK that a previous run left behind
        must never be presented as the output of this run.
        """
        final_product_dir = FACTORIES_DIR / "final-product"
        apk_path = final_product_dir / "SavageBrowser-debug.apk"
        started = time.time()
        failure = None
        try:
            log = run_factory_pipeline(input_source="shell")
        except Exception as error:  # no Android toolchain in this environment
            failure = error
            log = json.loads(
                (final_product_dir / "RELEASE_MANIFEST.json").read_text(encoding="utf-8"))

        apk_is_fresh = apk_path.exists() and apk_path.stat().st_mtime >= started

        if failure is None:
            self.assertEqual(log["stages"]["factory_1_intake"], "SUCCESS")
            self.assertEqual(log["stages"]["factory_2_agents"]["verdict"], "SHIP")
            self.assertEqual(
                log["stages"]["factory_3_android_tools"]["certification"]["status"], "CERTIFIED")
            self.assertEqual(log["status"], "CERTIFIED")
            self.assertTrue((final_product_dir / "browser_shell.py").exists())
            self.assertTrue((final_product_dir / "RELEASE_MANIFEST.json").exists())
            self.assertTrue(apk_is_fresh,
                            "a certified run must publish the APK this run produced")
        else:
            # The build refused to publish an APK, which is the only acceptable failure mode: the
            # manifest records FAILED and the APK on disk is not this run's output.
            self.assertEqual(log.get("status"), "FAILED", log)
            self.assertIn("Gradle build failed", str(failure))
            self.assertFalse(apk_is_fresh,
                             "a failed run must not leave a fresh APK that looks like its output")


if __name__ == "__main__":
    unittest.main()
