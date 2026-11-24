"""Mutation tests for the no-false-green gate itself.

A verifier that has never been made to fail is not evidence. These tests create
small real git repositories and prove that the gate rejects a failed exit code,
a top-level green wrapper around a failed nested result, a stub marker, an
uncovered changed file, and a missing executable.
"""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
STRICT_VERIFY = ROOT / "tools" / "nova" / "strict_verify.py"
CHECK_ARTIFACTS = ROOT / "tools" / "nova" / "check_artifacts.py"


def git(repo: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", "-c", "user.name=Gate Test", "-c", "user.email=gate@example.invalid", *args],
        cwd=str(repo),
        capture_output=True,
        text=True,
        check=True,
    )
    return completed.stdout


def base_contract(commands: list[dict]) -> dict:
    return {
        "schema_version": 1,
        "name": "strict verify mutation fixture",
        "scope_globs": ["**"],
        "inspection_globs": ["source/*.py", "contract.json"],
        "allowed_binary_paths": [],
        "allowed_binary_hashes": {},
        "required_files": ["source/app.py"],
        "required_patterns": [
            {
                "id": "source-really-exists",
                "globs": ["source/*.py"],
                "patterns": ["REAL\\s*=\\s*\\d+"],
            }
        ],
        "forbidden_patterns": [
            {
                "id": "no-stub-markers",
                "globs": ["source/*.py"],
                "patterns": ["TODO", "NotImplementedError"],
                "strip_comments": True,
            }
        ],
        "source_claims": [
            {
                "id": "one-real-assignment",
                "kind": "regex_count",
                "path": "source/app.py",
                "regex": r"REAL\s*=\s*\d+",
                "min": 1,
                "max": 1,
            }
        ],
        "commands": commands,
        "minimum_commands_by_profile": {"local": len(commands), "ci": len(commands)},
    }


def ok_command() -> dict:
    return {
        "id": "explicit-success",
        "argv": ["python3", "-c", "print('OK')"],
        "cwd": ".",
        "profiles": ["local", "ci"],
        "timeout": 30,
        "expect_exit": 0,
        "required_stdout": ["(?m)^OK$"],
        "forbidden_stdout": ["FAILED", "Traceback", "skipped="],
    }


class StrictVerifyFixture:
    def __init__(self, root: Path, commands: list[dict] | None = None):
        self.root = root
        (root / "source").mkdir(parents=True, exist_ok=True)
        (root / "source" / "app.py").write_text("REAL = 1\n", encoding="utf-8")
        (root / "contract.json").write_text(
            json.dumps(base_contract(commands or [ok_command()]), indent=2) + "\n",
            encoding="utf-8",
        )
        git(root, "init", "-q")
        git(root, "add", ".")
        git(root, "commit", "-q", "-m", "base")
        self.base = git(root, "rev-parse", "HEAD").strip()
        (root / "source" / "app.py").write_text("REAL = 2\n", encoding="utf-8")
        git(root, "add", ".")
        git(root, "commit", "-q", "-m", "change")

    def write_contract(self, commands: list[dict]) -> None:
        (self.root / "contract.json").write_text(
            json.dumps(base_contract(commands), indent=2) + "\n", encoding="utf-8"
        )
        git(self.root, "add", "contract.json")
        git(self.root, "commit", "-q", "-m", "contract")

    def run_gate(self) -> tuple[int, dict]:
        evidence = self.root / "evidence.json"
        log = self.root / "commands.log"
        completed = subprocess.run(
            [
                "python3",
                str(STRICT_VERIFY),
                "--repo",
                str(self.root),
                "--contract",
                str(self.root / "contract.json"),
                "--base",
                self.base,
                "--head",
                "HEAD",
                "--profile",
                "local",
                "--evidence",
                str(evidence),
                "--log",
                str(log),
            ],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            check=False,
        )
        if not evidence.is_file():
            raise AssertionError(
                f"gate did not write evidence; stdout={completed.stdout} stderr={completed.stderr}"
            )
        return completed.returncode, json.loads(evidence.read_text(encoding="utf-8"))


class StrictVerifyTest(unittest.TestCase):
    def test_explicit_evidence_passes(self):
        with tempfile.TemporaryDirectory() as temp:
            fixture = StrictVerifyFixture(Path(temp))
            code, evidence = fixture.run_gate()
            self.assertEqual(code, 0, evidence.get("errors"))
            self.assertEqual(evidence["status"], "PASS")
            self.assertEqual(evidence["executed_command_count"], 1)
            self.assertTrue((Path(temp) / "commands.log").read_text(encoding="utf-8"))
            fingerprint = evidence["worktree_fingerprint"]
            self.assertEqual(64, len(fingerprint["sha256"]))
            self.assertEqual(
                hashlib.sha256((Path(temp) / "source" / "app.py").read_bytes()).hexdigest(),
                fingerprint["file_sha256"]["source/app.py"],
            )
            self.assertIn(
                "evidence.json", evidence["verification_outputs_excluded_from_fingerprint"]
            )
            self.assertIn(
                "commands.log", evidence["verification_outputs_excluded_from_fingerprint"]
            )

    def test_top_level_green_cannot_mask_failed_nested_result(self):
        false_green = {
            "id": "false-green-wrapper",
            "argv": [
                "python3",
                "-c",
                "print('{\"ok\": true, \"result\": {\"ok\": false}}')",
            ],
            "cwd": ".",
            "profiles": ["local", "ci"],
            "timeout": 30,
            "expect_exit": 0,
            "required_stdout": ['"ok": true'],
            "json_path": "result",
            "json_assertions": [{"path": "ok", "equals": True}],
        }
        with tempfile.TemporaryDirectory() as temp:
            fixture = StrictVerifyFixture(Path(temp))
            fixture.write_contract([false_green])
            code, evidence = fixture.run_gate()
            self.assertEqual(code, 1)
            self.assertEqual(evidence["status"], "FAIL")
            joined = " ".join(evidence["errors"])
            self.assertIn("false-green-wrapper", joined)
            self.assertIn("expected True", joined)

    def test_failed_exit_code_is_rejected(self):
        failing = dict(ok_command())
        failing["argv"] = ["python3", "-c", "import sys; sys.exit(3)"]
        failing["required_stdout"] = []
        with tempfile.TemporaryDirectory() as temp:
            fixture = StrictVerifyFixture(Path(temp))
            fixture.write_contract([failing])
            code, evidence = fixture.run_gate()
            self.assertEqual(code, 1)
            self.assertIn("exit 3, expected 0", " ".join(evidence["errors"]))

    def test_stub_marker_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            fixture = StrictVerifyFixture(Path(temp))
            (fixture.root / "source" / "app.py").write_text(
                "REAL = 3\nTODO = 'stub'\n", encoding="utf-8"
            )
            git(fixture.root, "add", ".")
            git(fixture.root, "commit", "-q", "-m", "stub")
            code, evidence = fixture.run_gate()
            self.assertEqual(code, 1)
            self.assertIn("no-stub-markers", " ".join(evidence["errors"]))

    def test_uncovered_changed_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            fixture = StrictVerifyFixture(Path(temp))
            (fixture.root / "notes.txt").write_text("unreviewed\n", encoding="utf-8")
            git(fixture.root, "add", ".")
            git(fixture.root, "commit", "-q", "-m", "uncovered")
            code, evidence = fixture.run_gate()
            self.assertEqual(code, 1)
            self.assertIn("no inspection coverage", " ".join(evidence["errors"]))

    def test_missing_executable_fails_closed(self):
        missing = dict(ok_command())
        missing["argv"] = ["nova-tool-that-does-not-exist"]
        missing["required_stdout"] = []
        with tempfile.TemporaryDirectory() as temp:
            fixture = StrictVerifyFixture(Path(temp))
            fixture.write_contract([missing])
            code, evidence = fixture.run_gate()
            self.assertEqual(code, 1)
            self.assertIn("explicit-success", " ".join(evidence["errors"]))

    def test_artifact_checker_refuses_to_infer_sdk(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            evidence = root / "artifacts.json"
            env = os.environ.copy()
            env.pop("NOVA_BUILD_TOOLS", None)
            completed = subprocess.run(
                [
                    "python3",
                    str(CHECK_ARTIFACTS),
                    "--repo",
                    str(root),
                    "--evidence",
                    str(evidence),
                ],
                cwd=str(ROOT),
                env=env,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(completed.returncode, 1)
            payload = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertFalse(payload["ok"])
            self.assertIn("NOVA_BUILD_TOOLS is required", " ".join(payload["errors"]))

    def test_artifact_checker_json_stdout_is_parseable(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            env = os.environ.copy()
            env.pop("NOVA_BUILD_TOOLS", None)
            completed = subprocess.run(
                [
                    "python3",
                    str(CHECK_ARTIFACTS),
                    "--repo",
                    str(root),
                    "--evidence",
                    str(root / "artifacts.json"),
                    "--json",
                ],
                cwd=str(ROOT),
                env=env,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(completed.returncode, 1)
            stdout_payload = json.loads(completed.stdout)
            self.assertFalse(stdout_payload["ok"])
            self.assertIn("[check-artifacts] FAIL:", completed.stderr)
            # A FAIL diagnostic must not leak into JSON stdout; it belongs on
            # stderr so strict verifiers can parse the machine-readable payload.
            self.assertNotIn("[check-artifacts] FAIL:", completed.stdout)
            self.assertNotIn("PASS: four APKs", completed.stdout)


if __name__ == "__main__":
    unittest.main()
