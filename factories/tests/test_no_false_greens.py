"""False-green sentinel suite.

Every check here exists because a script or workflow once produced a green
flag without doing its task:
  * CI steps masked with `|| true` / `continue-on-error: true`
  * agents that printed findings but always exited 0
  * a manager that accepted REJECTED verdicts without failing
  * a dashboard test pointing at a file that did not exist
  * a knip station masking both a crash and vulnerable deps

If any of these regress, this suite goes red.
"""

from __future__ import annotations

import ast
import json
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _main_block_exits_meaningfully(path: Path) -> bool:
    """True when the file's `if __name__ == "__main__"` block calls
    sys.exit / raise SystemExit with a NON-CONSTANT argument (i.e. the exit
    code actually depends on results)."""
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    for node in tree.body:
        if not (isinstance(node, ast.If) and isinstance(node.test, ast.Compare)):
            continue
        src = ast.dump(node.test.left)
        if "__name__" not in src:
            continue
        calls = []
        for stmt in ast.walk(node):
            if isinstance(stmt, ast.Raise) and isinstance(stmt.exc, ast.Call):
                calls.append(stmt.exc)
            elif isinstance(stmt, ast.Expr) and isinstance(stmt.value, ast.Call):
                calls.append(stmt.value)
        for call in calls:
            func = call.func
            name = ""
            if isinstance(func, ast.Attribute):
                name = func.attr
            elif isinstance(func, ast.Name):
                name = func.id
            if name not in ("exit", "SystemExit") or not call.args:
                continue
            arg = call.args[0]
            # Honest = can exit non-zero: either a result-dependent (non-constant)
            # argument or an explicitly non-zero constant under a condition.
            if not isinstance(arg, ast.Constant):
                return True
            if arg.value not in (0, None):
                return True
    return False


class TestWorkflowsHaveNoSoftFailMasks(unittest.TestCase):
    def test_no_or_true_or_continue_on_error(self) -> None:
        violations = []
        for wf in sorted((ROOT / ".github" / "workflows").glob("*.yml")):
            for lineno, line in enumerate(wf.read_text(encoding="utf-8").splitlines(), 1):
                stripped = line.split("#", 1)[0]
                if "|| true" in stripped:
                    violations.append(f"{wf.name}:{lineno}: contains '|| true'")
                if "continue-on-error: true" in stripped:
                    violations.append(f"{wf.name}:{lineno}: continue-on-error: true")
        self.assertEqual(violations, [], "soft-fail masks must not return:\n" + "\n".join(violations))

    def test_run_all_has_no_or_true(self) -> None:
        for copy in (ROOT / "factory-2" / "scripts" / "run_all.sh",
                     ROOT / "factories" / "factory-2" / "scripts" / "run_all.sh"):
            self.assertNotIn("|| true", copy.read_text(encoding="utf-8"),
                             f"{copy} must not mask step failures")


class TestScriptsPropagateFailure(unittest.TestCase):
    SCRIPTS = [
        "factory-2/agents/agent_correctness.py",
        "factory-2/agents/agent_security.py",
        "factory-2/agents/agent_critic.py",
        "factory-2/agents/agent_model_debugger.py",
        "factory-2/manager/guidance.py",
        "factory-2/criticism/dead_code_check.py",
        "factory-2/criticism/incomplete_check.py",
        "factory-2/criticism/dead_buttons_check.py",
        "tools/schema_validator.py",
        "tools/anti_deception_auditor.py",
        "tools/reproducible_build_verifier.py",
    ]

    def test_main_blocks_exit_nonconstant(self) -> None:
        for rel in self.SCRIPTS:
            with self.subTest(script=rel):
                self.assertTrue(
                    _main_block_exits_meaningfully(ROOT / rel),
                    f"{rel}: __main__ must exit() with a result-dependent code, not unconditional 0",
                )

    def test_manager_fails_closed_on_missing_reports(self) -> None:
        src = (ROOT / "factory-2" / "manager" / "guidance.py").read_text(encoding="utf-8")
        self.assertIn("expected_reports", src, "manager must require the full agent report panel")
        self.assertIn('SystemExit(1 if final_verdict == "REJECTED" else 0)', src)

    def test_doctor_is_a_real_gate(self) -> None:
        src = (ROOT / ".factory" / "scripts" / "doctor.sh").read_text(encoding="utf-8")
        self.assertIn("exit 1", src, "doctor.sh must be able to fail")
        self.assertNotIn("DOCTOR CHECK COMPLETE\"\n", src.partition("exit 1")[0],
                         "doctor must not print COMPLETE before the failure path")


class TestScriptsCurrentlyGenuinelyClean(unittest.TestCase):
    """Because the scripts above CAN fail (proven by mutation testing),
    a green run here is a real green."""

    def _run(self, *argv: str) -> subprocess.CompletedProcess:
        return subprocess.run(argv, cwd=ROOT, capture_output=True, text=True, timeout=300)

    def test_correctness_agent_clean(self) -> None:
        res = self._run("python3", "factory-2/agents/agent_correctness.py")
        self.assertEqual(res.returncode, 0, res.stdout[-800:] + res.stderr[-800:])

    def test_dead_code_check_clean(self) -> None:
        res = self._run("python3", "factory-2/criticism/dead_code_check.py")
        self.assertEqual(res.returncode, 0, res.stdout[-800:] + res.stderr[-800:])

    def test_incomplete_check_clean(self) -> None:
        res = self._run("python3", "factory-2/criticism/incomplete_check.py")
        self.assertEqual(res.returncode, 0, res.stdout[-800:] + res.stderr[-800:])

    def test_doctor_clean(self) -> None:
        res = self._run("bash", ".factory/scripts/doctor.sh")
        self.assertEqual(res.returncode, 0, res.stdout[-800:] + res.stderr[-800:])


class TestDashboardAndToolingContracts(unittest.TestCase):
    def test_dashboard_matches_tool_registry(self) -> None:
        registry = json.loads((ROOT / "tools" / "factory" / "tools.json").read_text())
        rendered = (ROOT / "docs" / "factory" / "FACTORY.html").read_text(encoding="utf-8")
        rows = rendered.count('<tr data-state="')
        self.assertEqual(
            rows, len(registry["tools"]),
            "dashboard drift: regenerate via `python3 -m tools.factory.automation overview --publish-doc`",
        )
        self.assertNotIn("@@ROWS@@", rendered, "dashboard must be fully rendered, never a template")
        self.assertNotIn("@@COUNT@@", rendered)

    def test_test_script_chains_dashboard_harness(self) -> None:
        pkg = json.loads((ROOT / "package.json").read_text())
        self.assertIn("dashboard.test.cjs", pkg["scripts"]["test"],
                      "npm test must include the dashboard jsdom harness")
        self.assertNotIn("knip", pkg["scripts"],
                         "knip is retired (crash-prone 6.x / vulnerable 5.x); use audit:deps")

    def test_vitest_excludes_non_vitest_suites(self) -> None:
        cfg = (ROOT / "vitest.config.js").read_text()
        self.assertIn("tests/e2e/**", cfg, "playwright specs must not be swept into vitest")
        self.assertIn("tools/**/*.test.cjs", cfg, "dashboard cjs harness runs via npm test chain, not vitest")

    def test_conveyor_stage_fails_on_throw(self) -> None:
        src = (ROOT / "scripts" / "conveyor_belt.js").read_text()
        self.assertIn("throw error", src, "runStage must rethrow — swallowing errors is how stages lied")

    def test_dep_auditor_exists_and_knip_json_gone(self) -> None:
        self.assertTrue((ROOT / "scripts" / "dep_auditor.js").exists())
        self.assertFalse((ROOT / "knip.json").exists(), "knip.json must not return alongside knip")

    def test_snapshot_is_labeled_not_fabricated(self) -> None:
        snap = json.loads((ROOT / "docs" / "factory" / "verification-FACTORY002.json").read_text())
        self.assertIn("label", snap)
        self.assertIn("UNVERIFIED", snap.get("label", "") + json.dumps(snap.get("counts", {})),
                      "snapshot must honestly label its evidence level")


if __name__ == "__main__":
    unittest.main(verbosity=2)
