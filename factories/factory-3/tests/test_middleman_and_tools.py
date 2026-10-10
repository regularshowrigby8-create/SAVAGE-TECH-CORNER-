"""Unit tests for Factory 3 Android debugging toolkit and Middleman broker."""

import json
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent.parent
if (ROOT_DIR / "factories").exists():
    pass
elif (ROOT_DIR.parent / "factories").exists():
    ROOT_DIR = ROOT_DIR.parent

TOOLS_DIR = Path(__file__).resolve().parent.parent / "tools"

import sys
sys.path.insert(0, str(TOOLS_DIR))
sys.path.insert(0, str(ROOT_DIR / "factory-2" / "scripts"))
sys.path.insert(0, str(ROOT_DIR / "factories" / "factory-2" / "scripts"))

from debugging_toolkit import (
    Abi16KbPageSizeChecker,
    APKAnalyzer,
    InteractionAssertionValidator,
    LeakCanaryDetector,
    StrictModeDetector,
    UnverifiedExperimentalTools,
)
from middleman_broker import MiddlemanBroker
from model_debugger import ModelDebugger
from main_agent_verifier import MainAgentVerifier


class TestMiddlemanAndDebuggingTools(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp_dir = tempfile.mkdtemp()
        self.tmp_path = Path(self.tmp_dir)
        self.inbox_dir = self.tmp_path / "inbox"
        self.proposals_dir = self.tmp_path / "proposals"
        self.inbox_dir.mkdir(parents=True, exist_ok=True)
        self.proposals_dir.mkdir(parents=True, exist_ok=True)

        self.broker = MiddlemanBroker(inbox_dir=self.inbox_dir)
        self.debugger = ModelDebugger(proposals_dir=self.proposals_dir)
        self.verifier = MainAgentVerifier(proposals_dir=self.proposals_dir)

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def test_leak_canary_detector_flags_static_activity(self) -> None:
        detector = LeakCanaryDetector()
        test_file = self.tmp_path / "LeakyActivity.java"
        test_file.write_text("public class LeakyActivity { static Activity leakInstance; }\n", encoding="utf-8")
        findings = detector.scan_file(test_file)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0]["category"], "MEMORY_LEAK")

    def test_strict_mode_detector_flags_synchronous_network(self) -> None:
        detector = StrictModeDetector()
        test_file = self.tmp_path / "NetworkWorker.py"
        test_file.write_text("def fetch_sync():\n    return requests.get('https://api.test')\n", encoding="utf-8")
        findings = detector.scan_file(test_file)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0]["category"], "MAIN_THREAD_IO")

    def test_interaction_validator_flags_dead_button(self) -> None:
        validator = InteractionAssertionValidator()
        test_file = self.tmp_path / "view.py"
        test_file.write_text("register_button('btn_bad', 'Bad', None)\n", encoding="utf-8")
        findings = validator.validate(self.tmp_path)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0]["category"], "DEAD_BUTTON")

    def test_middleman_dispatches_error_packet_to_factory_2(self) -> None:
        # Create a file with an issue
        app_file = self.tmp_path / "sample_app.py"
        app_file.write_text("static Context sContext;\n", encoding="utf-8")

        audit_res = self.broker.audit_target(self.tmp_path)
        self.assertEqual(audit_res["status"], "RETURNED_TO_AGENTS_FOR_REMEDATION")

        # Verify error packet in inbox
        packet_files = list(self.inbox_dir.glob("*.json"))
        self.assertEqual(len(packet_files), 1)
        packet_data = json.loads(packet_files[0].read_text(encoding="utf-8"))
        self.assertIn("findings", packet_data)
        self.assertIn("model_insight_prompt", packet_data)

    def test_clean_code_passes_factory_3(self) -> None:
        clean_dir = self.tmp_path / "clean_app"
        clean_dir.mkdir()
        clean_file = clean_dir / "clean.py"
        clean_file.write_text("def run():\n    return 'clean'\n", encoding="utf-8")
        audit_res = self.broker.audit_target(clean_dir)
        self.assertEqual(audit_res["status"], "PASSED_FACTORY_3_CERTIFICATION")
        self.assertTrue(audit_res["clean"])


if __name__ == "__main__":
    unittest.main()
