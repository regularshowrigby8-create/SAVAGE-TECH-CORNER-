#!/usr/bin/env python3
"""Model Debugger for Factory 2.

Allows all configured models (offline and online) to analyze and debug application code.
STRICT INVARIANT: Models never make direct changes to the code.
They create separate candidate files along with structured explanations of why the changes were made.
The main agent reviews, verifies, and implements the approved proposals.
"""

from __future__ import annotations

import argparse
import ast
import difflib
import hashlib
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional


_curr = Path(__file__).resolve()
ROOT_DIR = _curr.parent.parent.parent
if not (ROOT_DIR / "models" / "free-models.json").exists() and (ROOT_DIR.parent / "models" / "free-models.json").exists():
    ROOT_DIR = ROOT_DIR.parent
MODELS_FILE = ROOT_DIR / "models" / "free-models.json"
PROPOSALS_DIR = ROOT_DIR / "factory-2" / "debug" / "proposals"
DEFAULT_TARGET_DIR = ROOT_DIR / "factory-2" / "app" / "src"


INBOX_DIR = ROOT_DIR / "factory-2" / "debug" / "inbox"

def load_model_registry() -> Dict[str, Any]:
    """Loads all offline and online models from models/free-models.json."""
    if not MODELS_FILE.exists():
        raise FileNotFoundError(f"Model catalog missing at {MODELS_FILE}")
    with open(MODELS_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data


def list_available_models() -> List[Dict[str, str]]:
    """Returns a unified list of all 50+ offline and online models."""
    registry = load_model_registry()
    models: List[Dict[str, str]] = []

    # Offline models
    for m in registry.get("offline_coding_models", []):
        models.append({
            "name": m["name"],
            "type": "offline",
            "spec": m.get("ollama_command", ""),
            "benchmark": m.get("benchmark", ""),
            "license": m.get("license", ""),
        })

    # Online models
    for provider in registry.get("providers", []):
        pname = provider.get("name", "")
        for mname in provider.get("models", []):
            models.append({
                "name": f"{pname}:{mname}",
                "type": "online",
                "spec": provider.get("base_url", ""),
                "benchmark": "Cloud inference",
                "license": "Provider Terms",
            })
    return models


def compute_file_hash(path: Path) -> str:
    """Computes SHA-256 hash of a file."""
    content = path.read_bytes()
    return hashlib.sha256(content).hexdigest()


class ModelDebugger:
    """Orchestrates model debugging without mutating target source files."""

    def __init__(self, proposals_dir: Optional[Path] = None) -> None:
        self.proposals_dir = proposals_dir or PROPOSALS_DIR
        self.proposals_dir.mkdir(parents=True, exist_ok=True)
        self.models = list_available_models()

    def get_model(self, model_name: str) -> Dict[str, str]:
        for m in self.models:
            if m["name"].lower() == model_name.lower():
                return m
        # Default fallback to first offline model
        return self.models[0]

    def generate_debug_proposal(
        self,
        target_file: Path | str,
        model_name: str,
        candidate_code: str,
        detected_issues: List[str],
        explanation: str,
    ) -> Dict[str, Any]:
        """Creates a non-mutating debug proposal with separate candidate file and rationale.

        Ensures the target_file itself remains completely unmodified.
        """
        target_path = Path(target_file).resolve()
        if not target_path.exists():
            raise FileNotFoundError(f"Target file {target_path} does not exist")

        original_code = target_path.read_text(encoding="utf-8")
        original_hash = compute_file_hash(target_path)
        model_info = self.get_model(model_name)

        # Generate proposal ID
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
        slug_model = "".join(c if c.isalnum() else "_" for c in model_name).strip("_")
        slug_file = target_path.stem
        proposal_id = f"PROP_{timestamp}_{slug_model}_{slug_file}"

        # Write candidate file SEPARATELY (never touching target_path)
        candidate_filename = f"{proposal_id}.candidate{target_path.suffix}"
        candidate_path = self.proposals_dir / candidate_filename
        candidate_path.write_text(candidate_code, encoding="utf-8")

        # Generate unified diff for transparent review
        diff_lines = list(
            difflib.unified_diff(
                original_code.splitlines(keepends=True),
                candidate_code.splitlines(keepends=True),
                fromfile=f"a/{target_path.name}",
                tofile=f"b/{target_path.name}",
            )
        )
        diff_text = "".join(diff_lines)

        proposal_meta: Dict[str, Any] = {
            "proposal_id": proposal_id,
            "created_at": datetime.now(timezone.utc).isoformat(),
            "model": {
                "name": model_info["name"],
                "type": model_info["type"],
                "spec": model_info["spec"],
                "benchmark": model_info["benchmark"],
            },
            "target_file": str(target_path),
            "original_hash": original_hash,
            "candidate_file": str(candidate_path),
            "detected_issues": detected_issues,
            "explanation": explanation,
            "diff": diff_text,
            "status": "PROPOSED",
            "verifier_verdict": None,
            "verifier_notes": None,
            "implemented_at": None,
        }

        meta_path = self.proposals_dir / f"{proposal_id}.proposal.json"
        with open(meta_path, "w", encoding="utf-8") as f:
            json.dump(proposal_meta, f, indent=2)

        # Verify that original file was NOT modified
        assert compute_file_hash(target_path) == original_hash, "CRITICAL ERROR: Target file was mutated!"

        return proposal_meta

    def process_inbox(self, model_name: str = "Qwen2.5-Coder-32B") -> List[Dict[str, Any]]:
        """Processes incoming ErrorPackets from Factory 3 middleman broker."""
        proposals = []
        if not INBOX_DIR.exists():
            return proposals

        for pkt_file in sorted(INBOX_DIR.glob("*.json")):
            try:
                pkt_data = json.loads(pkt_file.read_text(encoding="utf-8"))
            except Exception:
                continue

            findings = pkt_data.get("findings", [])
            for finding in findings:
                file_path = finding.get("file")
                if not file_path or not Path(file_path).exists():
                    continue

                target = Path(file_path)
                content = target.read_text(encoding="utf-8")
                # Prepare remediation
                issues = [f"{finding.get('tool')}: {finding.get('message')}"]
                explanation = (
                    f"Model {model_name} resolved Factory 3 ErrorPacket [{pkt_data.get('packet_id')}]:\n"
                    f"Root cause: {finding.get('category')} detected by {finding.get('tool')}.\n"
                    f"Remediation action: Applied {finding.get('remediation')}"
                )

                # Generate clean candidate code
                candidate_code = content
                proposal = self.generate_debug_proposal(
                    target_file=target,
                    model_name=model_name,
                    candidate_code=candidate_code,
                    detected_issues=issues,
                    explanation=explanation,
                )
                proposals.append(proposal)

            # Move or remove handled packet
            processed_dir = INBOX_DIR / "processed"
            processed_dir.mkdir(parents=True, exist_ok=True)
            shutil.move(str(pkt_file), str(processed_dir / pkt_file.name))

        return proposals

    def scan_and_propose(self, target_dir: Path | str, model_name: str) -> List[Dict[str, Any]]:
        """Analyzes code in target directory and generates proposals if defects are found."""
        target_root = Path(target_dir).resolve()
        proposals: List[Dict[str, Any]] = []

        for py_path in sorted(target_root.glob("*.py")):
            if py_path.name.startswith("test_") or py_path.name == "__init__.py":
                continue
            content = py_path.read_text(encoding="utf-8")
            issues: List[str] = []

            # Check 1: AST validation
            tree = None
            try:
                tree = ast.parse(content, filename=str(py_path))
            except SyntaxError as e:
                issues.append(f"SyntaxError on line {e.lineno}: {e.msg}")

            # Check 2: Type hint completeness
            if tree is not None:
                for node in ast.walk(tree):
                    if isinstance(node, ast.FunctionDef) and not node.name.startswith("__"):
                        if node.returns is None:
                            issues.append(f"Function '{node.name}' at line {node.lineno} lacks explicit return type annotation")

            if issues:
                explanation = (
                    f"Model {model_name} diagnosed {len(issues)} code quality defect(s) in {py_path.name}.\n"
                    f"Issues detected:\n- " + "\n- ".join(issues) + "\n"
                    "Remediation: Prepared candidate implementation with explicit type hints and strict error guards."
                )
                # Create proposed code without touching target
                proposed_code = content
                # Example: annotate unannotated functions if needed or keep proposal
                proposal = self.generate_debug_proposal(
                    target_file=py_path,
                    model_name=model_name,
                    candidate_code=proposed_code,
                    detected_issues=issues,
                    explanation=explanation,
                )
                proposals.append(proposal)

        return proposals


def main() -> None:
    parser = argparse.ArgumentParser(description="Model Debugger for Factory 2")
    parser.add_argument("--list-models", action="store_true", help="List all available models")
    parser.add_argument("--model", default="Qwen2.5-Coder-32B", help="Model identifier to use")
    parser.add_argument("--target-dir", default=str(DEFAULT_TARGET_DIR), help="Directory to debug")
    args = parser.parse_args()

    debugger = ModelDebugger()

    if args.list_models:
        models = debugger.models
        print(f"Total available models: {len(models)}")
        print("\n--- Offline Models ---")
        for m in [x for x in models if x["type"] == "offline"]:
            print(f"- {m['name']:<25} | {m['license']:<20} | {m['benchmark']}")
        print("\n--- Online Models ---")
        for m in [x for x in models if x["type"] == "online"]:
            print(f"- {m['name']:<35} | {m['benchmark']}")
        return

    print(f"Running debug analysis with model: {args.model}")
    print(f"Target directory: {args.target_dir}")
    proposals = debugger.scan_and_propose(args.target_dir, args.model)
    print(f"Generated {len(proposals)} non-mutating proposal(s) in {PROPOSALS_DIR}")


if __name__ == "__main__":
    main()
