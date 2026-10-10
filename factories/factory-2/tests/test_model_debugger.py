"""Comprehensive test suite for Model Debugger and Main Agent Verifier."""

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent.parent / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from main_agent_verifier import MainAgentVerifier
from model_debugger import (
    ModelDebugger,
    compute_file_hash,
    list_available_models,
    load_model_registry,
)


class TestModelDebuggerAndVerifier(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp_dir = tempfile.mkdtemp()
        self.tmp_path = Path(self.tmp_dir)
        self.proposals_dir = self.tmp_path / "proposals"
        self.target_dir = self.tmp_path / "app"
        self.proposals_dir.mkdir(parents=True, exist_ok=True)
        self.target_dir.mkdir(parents=True, exist_ok=True)

        self.debugger = ModelDebugger(proposals_dir=self.proposals_dir)
        self.verifier = MainAgentVerifier(proposals_dir=self.proposals_dir)

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def test_model_catalog_contains_at_least_50_models(self) -> None:
        models = list_available_models()
        self.assertGreaterEqual(len(models), 50, f"Expected >= 50 models, got {len(models)}")
        offline_count = sum(1 for m in models if m["type"] == "offline")
        online_count = sum(1 for m in models if m["type"] == "online")
        self.assertGreaterEqual(offline_count, 30)
        self.assertGreaterEqual(online_count, 20)

    def test_debugger_creates_proposal_without_mutating_target(self) -> None:
        # Create a sample target file
        target_file = self.target_dir / "sample.py"
        original_code = "def calculate_total(a, b):\n    return a + b\n"
        target_file.write_text(original_code, encoding="utf-8")
        original_hash = compute_file_hash(target_file)

        # Candidate proposal
        candidate_code = "def calculate_total(a: int, b: int) -> int:\n    \"\"\"Sum two integers.\"\"\"\n    return a + b\n"
        explanation = "Added type hints and docstring to calculate_total."

        proposal = self.debugger.generate_debug_proposal(
            target_file=target_file,
            model_name="Qwen2.5-Coder-32B",
            candidate_code=candidate_code,
            detected_issues=["missing_type_hints"],
            explanation=explanation,
        )

        # 1. Target file MUST be unchanged
        self.assertEqual(target_file.read_text(encoding="utf-8"), original_code)
        self.assertEqual(compute_file_hash(target_file), original_hash)

        # 2. Candidate file must exist separately
        candidate_path = Path(proposal["candidate_file"])
        self.assertTrue(candidate_path.exists())
        self.assertNotEqual(str(candidate_path), str(target_file))
        self.assertEqual(candidate_path.read_text(encoding="utf-8"), candidate_code)

        # 3. Metadata proposal file must exist and contain explanation
        meta_file = self.proposals_dir / f"{proposal['proposal_id']}.proposal.json"
        self.assertTrue(meta_file.exists())
        with open(meta_file, "r", encoding="utf-8") as f:
            meta = json.load(f)
        self.assertEqual(meta["explanation"], explanation)
        self.assertEqual(meta["status"], "PROPOSED")

    def test_main_agent_verifies_and_implements_valid_proposal(self) -> None:
        target_file = self.target_dir / "service.py"
        initial_code = "def get_status():\n    return 'idle'\n"
        target_file.write_text(initial_code, encoding="utf-8")

        improved_code = "def get_status() -> str:\n    return 'active'\n"
        proposal = self.debugger.generate_debug_proposal(
            target_file=target_file,
            model_name="DeepSeek-R1-Distill-32B",
            candidate_code=improved_code,
            detected_issues=["status_outdated"],
            explanation="Updated status to active with return type annotation.",
        )

        # Main agent processes proposals
        results = self.verifier.process_proposals(auto_implement=True)
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["status"], "IMPLEMENTED")
        self.assertEqual(results[0]["verifier_verdict"], "APPROVED")

        # Now target file MUST be updated with approved code
        self.assertEqual(target_file.read_text(encoding="utf-8"), improved_code)

    def test_main_agent_rejects_syntax_broken_proposal(self) -> None:
        target_file = self.target_dir / "faulty.py"
        initial_code = "def good():\n    return 42\n"
        target_file.write_text(initial_code, encoding="utf-8")
        original_hash = compute_file_hash(target_file)

        broken_code = "def bad_syntax(:\n    return 0\n"
        proposal = self.debugger.generate_debug_proposal(
            target_file=target_file,
            model_name="StarCoder2-15B",
            candidate_code=broken_code,
            detected_issues=["needs_refactoring"],
            explanation="Attempted refactoring.",
        )

        # Verifier runs
        results = self.verifier.process_proposals(auto_implement=True)
        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["status"], "REJECTED")
        self.assertEqual(results[0]["verifier_verdict"], "REJECTED")

        # Target file must remain untouched
        self.assertEqual(compute_file_hash(target_file), original_hash)


if __name__ == "__main__":
    unittest.main()
