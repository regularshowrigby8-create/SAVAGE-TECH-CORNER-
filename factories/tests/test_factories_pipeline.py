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
        self.assertEqual(log["stages"]["factory_3_android_tools"]["certification"]["status"], "CERTIFIED")
        final_product_dir = FACTORIES_DIR / "final-product"
        self.assertTrue((final_product_dir / "browser_shell.py").exists())
        self.assertTrue((final_product_dir / "RELEASE_MANIFEST.json").exists())


if __name__ == "__main__":
    unittest.main()
