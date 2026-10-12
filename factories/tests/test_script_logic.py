"""Logic verification suite — truth tables for every gate script.

test_no_false_greens.py proves scripts CAN fail and are currently clean.
This suite proves the scripts *reason correctly*: each check must fire on
crafted true positives, stay silent on crafted true negatives, and the
producer/consumer policies (agents -> reports -> critic/manager -> schema)
must be mutually consistent.

Method: every walk-the-repo scanner is executed as a subprocess with its
cwd pointed at a synthetic tmp tree, reports are written there, nothing in
the real repo is touched.
"""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _run(script: Path, cwd: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(script), *args],
        cwd=cwd, capture_output=True, text=True, timeout=120,
    )


def _mk_tree(tmp: Path, files: dict[str, str]) -> None:
    for rel, content in files.items():
        target = tmp / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(textwrap.dedent(content), encoding="utf-8")


VALID_CATALOG = {
    "offline_coding_models": [{"id": f"m{i}"} for i in range(12)],
    "providers": [],
}


class TestCorrectnessAgentLogic(unittest.TestCase):
    SCRIPT = ROOT / "factory-2" / "agents" / "agent_correctness.py"

    def _scan(self, files: dict[str, str]) -> list[dict]:
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            _mk_tree(tmp, files)
            (tmp / "factory-2" / "reports").mkdir(parents=True, exist_ok=True)
            res = _run(self.SCRIPT, tmp)
            report = (tmp / "factory-2" / "reports" / "correctness_report.md").read_text()
            import ast as _ast
            findings = _ast.literal_eval(report.split("Findings:", 1)[1].strip())
            return findings, res.returncode

    def test_fires_on_dead_private_function_and_todo_and_unreachable(self) -> None:
        findings, rc = self._scan({
            "app.py": "\n" \
                "                # TO" \
                "DO: unfinished work here\n\n" \
                "                def _orphan_helper():\n" \
                "                    return 1\n\n" \
                "                def noisy():\n" \
                "                    return 5\n" \
                "                    print(\"unreachable\")\n" \
                "            ",
        })
        types = {f["type"] for f in findings}
        self.assertIn("dead_code_function", types)
        self.assertIn("incomplete_marker", types)
        self.assertIn("unreachable_code", types)
        self.assertEqual(rc, 1, "agent must exit 1 when findings exist")

    def test_silent_on_used_private_function(self) -> None:
        findings, rc = self._scan({
            "app.py": '''
                def _helper():
                    return 1

                def entry():
                    return _helper()
            ''',
        })
        self.assertEqual(findings, [])
        self.assertEqual(rc, 0)

    def test_sibling_file_reference_exempts_private_function(self) -> None:
        # Documented scope semantic: a private function referenced anywhere in
        # the same directory (even dynamically) is treated as used.
        findings, _ = self._scan({
            "pkg/a.py": "def _shared_helper():\n    return 1\n",
            "pkg/b.py": "HANDLERS = {'_shared_helper': object}\n",
        })
        self.assertNotIn("dead_code_function", {f["type"] for f in findings})

    def test_todo_without_colon_is_not_flagged(self) -> None:
        # Locked semantic: only separator-anchored markers count (name + colon).
        findings, _ = self._scan({"app.py": "# TODO add docs someday\nX = 1\n"})
        self.assertNotIn("incomplete_marker", {f["type"] for f in findings})

    def test_syntax_error_is_a_finding_not_a_skip(self) -> None:
        findings, rc = self._scan({"broken.py": "def broken(:\n    pass\n"})
        self.assertIn("syntax_error", {f["type"] for f in findings})
        self.assertEqual(rc, 1)


class TestSecurityAgentLogic(unittest.TestCase):
    SCRIPT = ROOT / "factory-2" / "agents" / "agent_security.py"

    def _scan(self, files: dict[str, str]) -> tuple[list[dict], int]:
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            _mk_tree(tmp, files)
            res = _run(self.SCRIPT, tmp)
            report = (tmp / "factory-2" / "reports" / "security_report.md").read_text()
            import ast as _ast
            return _ast.literal_eval(report.split("Findings:", 1)[1].strip()), res.returncode

    def test_aws_format_token_detected(self) -> None:
        findings, rc = self._scan({"keys.py": 'AWS_KEY = "AKIAIOSFODNN7EXAMPLE"\n'})  # nosec
        self.assertTrue(any(f["type"] == "hardcoded_token" for f in findings))
        self.assertEqual(rc, 1)

    def test_github_token_detected(self) -> None:
        token = "ghp_" + "A" * 40
        findings, _ = self._scan({"keys.py": f'T = "{token}"\n'})
        self.assertTrue(any(f["type"] == "hardcoded_token" for f in findings))

    def test_private_key_pem_detected(self) -> None:
        findings, _ = self._scan({"k.txt.py": 'PEM = "-----BEGIN RSA PRIVATE KEY-----"\n'})  # nosec
        self.assertTrue(any(f["type"] == "hardcoded_token" for f in findings))

    def test_high_entropy_password_flagged_by_widened_regex(self) -> None:
        # Regression lock: the old 4-name regex missed `password=`.
        findings, _ = self._scan({"cfg.py": 'password = "Xk9!mZq2PvL8nB4wTy"\n'})  # nosec
        self.assertTrue(any(f["type"] == "high_entropy_secret" for f in findings),
                        "password assignments with high entropy must be caught")

    def test_low_entropy_assignment_not_flagged(self) -> None:
        findings, rc = self._scan({"cfg.py": 'password = "letmeinletmeinxx"\n'})
        self.assertEqual([f for f in findings if f["type"] == "high_entropy_secret"], [])
        self.assertEqual(rc, 0)

    def test_mock_values_exempt(self) -> None:
        findings, _ = self._scan({"cfg.py": 'api_key = "example_mock_test_dummy_12345"\n'})
        self.assertEqual(findings, [])

    def test_shell_true_flagged_shell_false_clean(self) -> None:
        bad, _ = self._scan({"a.py": "import subprocess\nsubprocess.run('ls', shell=True)\n"})
        self.assertTrue(any(f["type"] == "insecure_subprocess_shell" for f in bad))
        good, rc = self._scan({"a.py": "import subprocess\nsubprocess.run(['ls'], shell=False)\n"})
        self.assertEqual(good, [])
        self.assertEqual(rc, 0)

    def test_webview_file_access_true_flagged_false_clean(self) -> None:
        bad, _ = self._scan({"A.java": "webView.getSettings().setAllowFileAccess(true);\n"})
        self.assertTrue(any(f["type"] == "insecure_file_access" for f in bad))
        good, rc = self._scan({"A.java": "webView.getSettings().setAllowFileAccess(false);\n"})
        self.assertEqual(good, [])
        self.assertEqual(rc, 0)

    def test_cleartext_manifest_flagged_and_ssl_proceed_flagged(self) -> None:
        bad, _ = self._scan({
            "AndroidManifest.xml": '<manifest><application android:usesCleartextTraffic="true"/></manifest>\n',
            "Web.java": "public void onReceivedSslError(WebView v, SslErrorHandler handler) { handler.proceed(); }\n",
        })
        types = {f["type"] for f in bad}
        self.assertIn("insecure_cleartext_traffic", types)
        self.assertIn("insecure_ssl_bypass", types)

    def test_clean_env_file_scanned(self) -> None:
        findings, _ = self._scan({".env": 'API_TOKEN="AKIAIOSFODNN7EXAMPLE"\n'})  # nosec
        self.assertTrue(any(f["type"] == "hardcoded_token" for f in findings),
                        ".env files must be inside the scanned extension set")


class TestIncompleteCheckLogic(unittest.TestCase):
    SCRIPT = ROOT / "factory-2" / "criticism" / "incomplete_check.py"

    def _scan(self, files: dict[str, str]) -> list[str]:
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            _mk_tree(tmp, files)
            _run(self.SCRIPT, tmp)
            return (tmp / "factory-2" / "reports" / "incomplete_criticism.md").read_text()

    def test_pass_only_body_flagged(self) -> None:
        report = self._scan({"app.py": "def stub():\n    pass\n"})
        self.assertIn("Stub function 'stub'", report)

    def test_dunder_pass_exempt(self) -> None:
        report = self._scan({"app.py": "class A:\n    def __noop__(self):\n        pass\n"})
        self.assertNotIn("Stub function", report)

    def test_unparseable_file_is_a_finding(self) -> None:
        report = self._scan({"broken.py": "def nope(:\n"})
        self.assertIn("Unparseable python file", report)


class TestDeadCodeCheckLogic(unittest.TestCase):
    SCRIPT = ROOT / "factory-2" / "criticism" / "dead_code_check.py"

    def _scan(self, files: dict[str, str]) -> list[str]:
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            _mk_tree(tmp, files)
            _run(self.SCRIPT, tmp)
            return (tmp / "factory-2" / "reports" / "dead_code_criticism.md").read_text()

    def test_uncalled_private_flagged(self) -> None:
        report = self._scan({"app.py": "def _never():\n    return 1\n"})
        self.assertIn("potentially unused private helper", report)

    def test_called_private_clean(self) -> None:
        report = self._scan({"app.py": "def _used():\n    return 1\ndef main():\n    return _used()\n"})
        self.assertIn("— PASS", report)

    def test_unparseable_file_is_a_finding(self) -> None:
        report = self._scan({"broken.py": "def nope(:\n"})
        self.assertIn("Unparseable python file", report)


class TestManagerLogic(unittest.TestCase):
    SCRIPT = ROOT / "factory-2" / "manager" / "guidance.py"

    PANEL = {
        "correctness_report.md": "# Correctness Report\nFindings: []\n",
        "security_report.md": "# Security Report\nFindings: []\n",
        "architecture_report.md": "# Architecture Report\nFindings: []\n",
        "quality_report.md": "# Quality Report\nFindings: []\n",
        "critic_report.md": "# Critic Report\nVerdict: SHIP\n",
        "model_debugger_report.md": "# Model Debugger Report\nFindings: []\n",
    }

    def _review(self, reports: dict[str, str]) -> tuple[str, int, str]:
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            _mk_tree(tmp, {f"factory-2/reports/{k}": v for k, v in reports.items()})
            res = _run(self.SCRIPT, tmp)
            response = (tmp / "factory-2" / "reports" / "manager_response.md").read_text()
            verdict = "REJECTED" if "Verdict: REJECTED" in response else "ACCEPTED"
            return verdict, res.returncode, response

    def test_full_clean_panel_accepted(self) -> None:
        verdict, rc, _ = self._review(self.PANEL)
        self.assertEqual((verdict, rc), ("ACCEPTED", 0))

    def test_missing_panel_member_rejected(self) -> None:
        reports = dict(self.PANEL)
        del reports["security_report.md"]
        verdict, rc, response = self._review(reports)
        self.assertEqual((verdict, rc), ("REJECTED", 1))
        self.assertIn("MISSING AGENT REPORTS", response)

    def test_malformed_report_rejected_not_accepted(self) -> None:
        reports = dict(self.PANEL)
        reports["correctness_report.md"] = "# Correctness Report\nFindings: this-is-not-a-list{\n"
        verdict, rc, response = self._review(reports)
        self.assertEqual((verdict, rc), ("REJECTED", 1))
        self.assertIn("MALFORMED REPORT", response)

    def test_report_without_findings_section_rejected(self) -> None:
        reports = dict(self.PANEL)
        reports["quality_report.md"] = "# Quality Report\nno structured findings here\n"
        verdict, rc, _ = self._review(reports)
        self.assertEqual((verdict, rc), ("REJECTED", 1))

    def test_blocking_security_type_rejected(self) -> None:
        reports = dict(self.PANEL)
        reports["security_report.md"] = "# Security Report\nFindings: [{'type': 'insecure_ssl_bypass', 'file': 'A.java'}]\n"
        verdict, rc, _ = self._review(reports)
        self.assertEqual((verdict, rc), ("REJECTED", 1))

    def test_blocking_correctness_types_rejected(self) -> None:
        for blocking_type in ("dead_code_function", "incomplete_marker", "unreachable_code", "syntax_error"):
            reports = dict(self.PANEL)
            reports["correctness_report.md"] = f"# Correctness Report\nFindings: [{{'type': '{blocking_type}'}}]\n"
            verdict, rc, _ = self._review(reports)
            self.assertEqual((verdict, rc), ("REJECTED", 1), f"{blocking_type} must block")

    def test_advisory_only_findings_accepted_as_advisory(self) -> None:
        reports = dict(self.PANEL)
        reports["security_report.md"] = "# Security Report\nFindings: [{'type': 'missing_host_allowlist_check', 'file': 'A.java'}]\n"
        verdict, rc, response = self._review(reports)
        self.assertEqual((verdict, rc), ("ACCEPTED", 0))
        self.assertIn("ADVISORY", response)

    def test_unknown_producer_rejected_fail_closed(self) -> None:
        reports = dict(self.PANEL)
        reports["rogue_report.md"] = "# Rogue\nFindings: []\n"
        verdict, rc, response = self._review(reports)
        self.assertEqual((verdict, rc), ("REJECTED", 1))
        self.assertIn("UNKNOWN PRODUCER", response)

    def test_critic_fix_first_rejected(self) -> None:
        reports = dict(self.PANEL)
        reports["critic_report.md"] = "# Critic Report\nVerdict: FIX-FIRST\n"
        verdict, rc, _ = self._review(reports)
        self.assertEqual((verdict, rc), ("REJECTED", 1))


class TestCriticLogic(unittest.TestCase):
    SCRIPT = ROOT / "factory-2" / "agents" / "agent_critic.py"

    BASE = {
        "models/free-models.json": json.dumps(VALID_CATALOG),
        "factory-2/reports/correctness_report.md": "# C\nFindings: []\n",
        "factory-2/reports/security_report.md": "# S\nFindings: []\n",
        "factory-2/reports/architecture_report.md": "# A\nFindings: []\n",
        "factory-2/reports/quality_report.md": "# Q\nFindings: []\n",
        "factory-2/reports/model_debugger_report.md": "# M\nFindings: []\n",
    }

    def _review(self, extra_files: dict[str, str]) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            _mk_tree(tmp, {**self.BASE, **extra_files})
            res = _run(self.SCRIPT, tmp)
            return res.returncode, res.stdout

    def test_clean_panel_ships(self) -> None:
        rc, out = self._review({})
        self.assertEqual(rc, 0)
        self.assertIn("SHIP", out)

    def test_dead_code_finding_blocks_via_expanded_set(self) -> None:
        rc, _ = self._review({
            "factory-2/reports/correctness_report.md": "# C\nFindings: [{'type': 'dead_code_function', 'file': 'a.py', 'line': 1}]\n",
        })
        self.assertEqual(rc, 1)

    def test_insecure_webview_family_blocks(self) -> None:
        rc, _ = self._review({
            "factory-2/reports/security_report.md": "# S\nFindings: [{'type': 'insecure_file_access', 'file': 'A.java'}]\n",
        })
        self.assertEqual(rc, 1)

    def test_missing_report_blocks_fail_closed(self) -> None:
        files = {k: v for k, v in self.BASE.items() if "correctness_report" not in k}
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            _mk_tree(tmp, files)
            res = _run(self.SCRIPT, tmp)
            self.assertEqual(res.returncode, 1, "a missing report must never sail through the critic")


class TestPolicyConsistencyAcrossProducers(unittest.TestCase):
    """Agents, critic and manager must agree on what blocks — divergent
    severity sets are how blocking findings used to be waved through."""

    def test_security_blocking_types_equal_manager_policy(self) -> None:
        security = _load_module("agent_security", ROOT / "factory-2" / "agents" / "agent_security.py")
        manager = _load_module("guidance", ROOT / "factory-2" / "manager" / "guidance.py")
        self.assertEqual(set(security.BLOCKING_TYPES), set(manager.BLOCKING_BY_AGENT["security"]))

    def test_critic_covers_manager_blocking_types(self) -> None:
        critic_src = (ROOT / "factory-2" / "agents" / "agent_critic.py").read_text()
        manager = _load_module("guidance", ROOT / "factory-2" / "manager" / "guidance.py")
        for agent, types in manager.BLOCKING_BY_AGENT.items():
            for blocking_type in types:
                self.assertIn(blocking_type, critic_src,
                              f"critic must treat manager-blocking type {blocking_type} ({agent}) as blocking")

    def test_manager_policy_covers_all_correctness_emitted_types(self) -> None:
        manager = _load_module("guidance", ROOT / "factory-2" / "manager" / "guidance.py")
        emitted = {"syntax_error", "unreachable_code", "dead_code_function", "incomplete_marker"}
        self.assertEqual(emitted, set(manager.BLOCKING_BY_AGENT["correctness"]))


class TestDepAuditorLogic(unittest.TestCase):
    SCRIPT = ROOT / "scripts" / "dep_auditor.js"

    def _run_audit(self, pkg: dict, sources: dict[str, str]) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            (tmp / "package.json").write_text(json.dumps(pkg))
            for rel, content in sources.items():
                target = tmp / "src" / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content)
            res = subprocess.run(["node", str(self.SCRIPT)], cwd=tmp,
                                 capture_output=True, text=True, timeout=60)
            return res.returncode, res.stdout + res.stderr

    def test_unused_dependency_flagged(self) -> None:
        rc, out = self._run_audit(
            {"name": "t", "devDependencies": {"eslint": "^9"}},
            {"index.js": "console.log('nothing imported');\n"},
        )
        self.assertEqual(rc, 1)
        self.assertIn("UNUSED DEPENDENCY", out)

    def test_undeclared_import_flagged(self) -> None:
        rc, out = self._run_audit(
            {"name": "t", "devDependencies": {}},
            {"index.js": 'import _ from "lodash";\n'},
        )
        self.assertEqual(rc, 1)
        self.assertIn("UNDECLARED IMPORT", out)

    def test_declared_import_passes(self) -> None:
        rc, _ = self._run_audit(
            {"name": "t", "devDependencies": {"lodash": "^4"}},
            {"index.js": 'import _ from "lodash";\n'},
        )
        self.assertEqual(rc, 0)

    def test_scoped_package_root_mapping(self) -> None:
        rc, out = self._run_audit(
            {"name": "t", "devDependencies": {"@scope/pkg": "^1"}},
            {"index.js": 'import x from "@scope/pkg/sub/deep";\n'},
        )
        self.assertEqual(rc, 0, out)

    def test_side_effect_import_counts_as_usage(self) -> None:
        # Regression lock: `import "pkg"` (no `from`) was invisible to the old regex.
        rc, _ = self._run_audit(
            {"name": "t", "devDependencies": {"polyfill-x": "^1"}},
            {"index.js": 'import "polyfill-x";\n'},
        )
        self.assertEqual(rc, 0)

    def test_undeclared_side_effect_import_flagged(self) -> None:
        rc, out = self._run_audit(
            {"name": "t", "devDependencies": {}},
            {"index.js": 'import "polyfill-x";\n'},
        )
        self.assertEqual(rc, 1)
        self.assertIn("polyfill-x", out)

    def test_builtins_never_flagged(self) -> None:
        rc, out = self._run_audit(
            {"name": "t", "devDependencies": {}},
            {"index.js": 'import fs from "fs";\nimport path from "node:path";\nconst cp = require("child_process");\n'},
        )
        self.assertEqual(rc, 0, out)


class TestAntiDeceptionLogic(unittest.TestCase):
    SCRIPT = ROOT / "tools" / "anti_deception_auditor.py"

    def _audit(self, files: dict[str, str]) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            _mk_tree(tmp, files)
            res = _run(self.SCRIPT, tmp, "--root", str(tmp))
            return res.returncode, res.stdout

    def test_foreign_token_detected(self) -> None:
        rc, out = self._audit({"app.py": 'ORIGIN = "fork of the light' 'ning browser"\n'})
        self.assertEqual(rc, 1)
        self.assertIn("light" "ning", out)

    def test_allowlisted_self_does_not_flag(self) -> None:
        # The auditor file itself is in the allowlist by exact relative path.
        content = (ROOT / "tools" / "anti_deception_auditor.py").read_text()
        rc, _ = self._audit({"tools/anti_deception_auditor.py": content})
        self.assertEqual(rc, 0, "the auditor's own token literals must be allowlisted by path")

    def test_hollow_return_true_verifier_flagged(self) -> None:
        rc, out = self._audit({"app.py": "def verify_result(x):\n    return True\n"})
        self.assertEqual(rc, 1)
        self.assertIn("Hollow verification", out)

    def test_honest_two_branch_verifier_clean(self) -> None:
        rc, _ = self._audit({
            "app.py": "def verify_result(x):\n    if x:\n        return True\n    return False\n"
        })
        self.assertEqual(rc, 0)

    def test_pass_only_function_flagged(self) -> None:
        rc, _ = self._audit({"app.py": "def hook():\n    pass\n"})
        self.assertEqual(rc, 1)

    def test_shell_gate_bypass_flagged(self) -> None:
        rc, out = self._audit({"run.sh": "#!/bin/sh\nif [ -n \"$SKIP_GATES\" ]; then exit 0; fi\n"})
        self.assertEqual(rc, 1)
        self.assertIn("bypass", out)

    def test_uninspectable_zip_is_fail_closed_violation(self) -> None:
        rc, out = self._audit({"artifact.zip": "PK-not-really-a-zip"})
        self.assertEqual(rc, 1)
        self.assertIn("Could not inspect archive", out)

    def test_dead_buttons_hollowness_probe(self) -> None:
        rc, out = self._audit({"factory-2/criticism/dead_buttons_check.py": "print('substring scan only')\n"})
        self.assertEqual(rc, 1)
        self.assertIn("hollow", out)


class TestDexVerifierLogic(unittest.TestCase):
    def setUp(self) -> None:
        self.verifier = _load_module("reproducible_build_verifier", ROOT / "tools" / "reproducible_build_verifier.py")

    def test_corrupted_fixture_dex_rejected(self) -> None:
        fixture = ROOT / "factories" / "tests" / "fixtures" / "corrupted_stock_classes.dex"
        errors = self.verifier.verify_dex_structure(fixture.read_bytes())
        self.assertTrue(errors, "the known-corrupt canary DEX must produce structural errors")

    def test_valid_fixture_dex_accepted(self) -> None:
        """Positive control: a structurally valid classes.dex extracted from the
        former tracked build artifact must pass verification (kept as a fixture so
        the gate's accepting path stays covered without any tracked APK)."""
        fixture = ROOT / "factories" / "tests" / "fixtures" / "valid_classes.dex"
        self.assertTrue(fixture.exists(), "valid_classes.dex fixture is missing")
        errors = self.verifier.verify_dex_structure(fixture.read_bytes())
        self.assertEqual(errors, [], f"valid classes.dex must stay structurally valid: {errors}")

    def test_v2_signing_block_detected_and_absent_on_garbage(self) -> None:
        """Build a minimal ZIP, then graft the real extracted v2/v3 signing-block
        bytes before its Central Directory. The detector must see the block; a
        garbage/malformed blob must read as unsigned."""
        import io
        import struct
        import zipfile

        block = (ROOT / "factories" / "tests" / "fixtures" / "real_v2_signing_block.bin").read_bytes()
        self.assertTrue(block, "real_v2_signing_block.bin fixture is missing/empty")

        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("classes.dex", b"dex\n035\x00" + b"\x00" * 200)
        unsigned = buf.getvalue()

        # Graft the signing block immediately before the Central Directory and
        # patch the EOCD cd_offset so the parser walks to it correctly.
        eocd = unsigned.rfind(b"PK\x05\x06")
        cd_offset = struct.unpack_from("<I", unsigned, eocd + 16)[0]
        signed = unsigned[:cd_offset] + block + unsigned[cd_offset:]
        signed = bytearray(signed)
        struct.pack_into("<I", signed, len(signed) - 22 + 16, cd_offset + len(block))

        with tempfile.TemporaryDirectory() as td:
            signed_path = Path(td) / "signed.apk"
            signed_path.write_bytes(bytes(signed))
            self.assertTrue(self.verifier._has_v2_signing_block(signed_path))

            unsigned_path = Path(td) / "unsigned.apk"
            unsigned_path.write_bytes(unsigned)
            self.assertFalse(self.verifier._has_v2_signing_block(unsigned_path))

            garbage_path = Path(td) / "garbage.apk"
            garbage_path.write_bytes(b"PK\x05\x06" + b"\x00" * 18)
            self.assertFalse(self.verifier._has_v2_signing_block(garbage_path))


class TestMiddlemanSchemaContract(unittest.TestCase):
    """Producer/consumer contract: middleman ErrorPackets must satisfy the
    schema validator — the mismatch (missing max_retries/status) was a real
    linkage break found in this round."""

    def _dispatch_packet(self, findings: list[dict]) -> tuple[dict, dict]:
        tools_dir = str(ROOT / "factory-3" / "tools")
        if tools_dir not in sys.path:
            sys.path.insert(0, tools_dir)
        broker_mod = _load_module("middleman_broker", ROOT / "factory-3" / "tools" / "middleman_broker.py")
        with tempfile.TemporaryDirectory() as td:
            broker = broker_mod.MiddlemanBroker(inbox_dir=Path(td))
            packet = broker.dispatch_error_packet(Path("shell/android_project"), findings)
            on_disk = json.loads((Path(td) / f"{packet['packet_id']}.json").read_text())
            return packet, on_disk

    def _validator(self):
        return _load_module("schema_validator", ROOT / "tools" / "schema_validator.py")

    def test_dispatched_packet_is_schema_conformant(self) -> None:
        validator = self._validator()
        packet, on_disk = self._dispatch_packet([
            {"severity": "ERROR", "tool": "APKAnalyzer", "message": "Classes.dex corrupt"},
        ])
        for candidate in (packet, on_disk):
            ok, errors = validator.validate_error_packet(candidate)
            self.assertTrue(ok, f"packet must validate cleanly, got: {errors}")

    def test_message_only_findings_get_normalized(self) -> None:
        validator = self._validator()
        packet, _ = self._dispatch_packet([{"message": "bare finding without protocol fields"}])
        ok, errors = validator.validate_error_packet(packet)
        self.assertTrue(ok, f"normalization must repair bare findings, got: {errors}")

    def test_tampered_packet_rejected(self) -> None:
        validator = self._validator()
        packet, _ = self._dispatch_packet([{"severity": "ERROR", "tool": "t", "message": "m"}])
        packet["retry_count"] = -99
        ok, _ = validator.validate_error_packet(packet)
        self.assertFalse(ok, "tampering must flip the verdict to invalid")


class TestConveyorGitignoreLogic(unittest.TestCase):
    def test_env_shielded_by_exact_rule_not_substring(self) -> None:
        lines = [l.strip() for l in (ROOT / ".gitignore").read_text().splitlines()
                 if l.strip() and not l.strip().startswith("#")]
        self.assertIn(".env", lines, "'­.env' must hold as an exact rule")
        self.assertIn(".coverage", lines)

    def test_conveyor_uses_line_exact_matching(self) -> None:
        src = (ROOT / "scripts" / "conveyor_belt.js").read_text()
        self.assertIn("gitignoreLines.includes(\".env\")", src,
                      "substring includes('.env') would let '.env.example' satisfy the shield")

    def test_substring_semantics_would_have_false_passed(self) -> None:
        # Proof that the old logic was wrong, not merely different:
        fake_gitignore = ".env.example\n"
        self.assertTrue(".env" in fake_gitignore, "old substring check passes without an .env shield")
        fake_lines = [l.strip() for l in fake_gitignore.splitlines() if l.strip()]
        self.assertNotIn(".env", fake_lines, "line-exact check correctly rejects it")


class TestDuplicationAuditorLogic(unittest.TestCase):
    def _drift_probe(self, rel: str, mutate: str) -> tuple[int, str]:
        target = ROOT / rel
        original = target.read_bytes()
        try:
            target.write_bytes(original + mutate.encode())
            res = subprocess.run([sys.executable, "tools/duplication_auditor.py"],
                                 cwd=ROOT, capture_output=True, text=True, timeout=60)
            return res.returncode, res.stdout
        finally:
            target.write_bytes(original)

    def test_clean_repo_passes(self) -> None:
        res = subprocess.run([sys.executable, "tools/duplication_auditor.py"],
                             cwd=ROOT, capture_output=True, text=True, timeout=60)
        self.assertEqual(res.returncode, 0, res.stdout)

    def test_mirror_drift_detected(self) -> None:
        rc, out = self._drift_probe("factories/factory-2/agents/agent_correctness.py", "\n# drift\n")
        self.assertEqual(rc, 1)
        self.assertIn("Drift detected", out)

    def test_missing_mirror_detected(self) -> None:
        target = ROOT / "factories/factory-2/criticism/dead_buttons_check.py"
        original = target.read_bytes()
        try:
            target.unlink()
            res = subprocess.run([sys.executable, "tools/duplication_auditor.py"],
                                 cwd=ROOT, capture_output=True, text=True, timeout=60)
            self.assertEqual(res.returncode, 1)
            self.assertIn("One-sided file", res.stdout)
        finally:
            target.write_bytes(original)


class TestDeadButtonsLogic(unittest.TestCase):
    """Shell-button execution is covered by the 7-button behavioral run in the
    parent suite; here: frontend binding semantics in both directions."""

    SCRIPT = ROOT / "factory-2" / "criticism" / "dead_buttons_check.py"

    def test_bound_by_getelementbyid_passes_unbound_fails(self) -> None:
        frontend = ROOT / "frontend"
        created_dir = not frontend.exists()
        frontend.mkdir(exist_ok=True)
        html = frontend / "zz_logic_probe.html"
        js = frontend / "zz_logic_probe.js"
        try:
            html.write_text('<!DOCTYPE html><html lang="en"><body>'
                            '<button id="zzbound">A</button>'
                            '<button id="zzunbound">B</button>'
                            '<button id="zzinline" onclick="go()">C</button>'
                            '</body></html>')
            js.write_text('document.getElementById("zzbound").addEventListener("click", () => {});\n')
            res = subprocess.run([sys.executable, str(self.SCRIPT)], cwd=ROOT,
                                 capture_output=True, text=True, timeout=120)
            self.assertEqual(res.returncode, 1)
            self.assertIn("zzunbound", res.stdout)
            self.assertNotIn('"zzbound" in', res.stdout, "bound button must not be flagged")
            self.assertNotIn("zzinline", res.stdout, "inline onclick button must not be flagged")
        finally:
            html.unlink(missing_ok=True)
            js.unlink(missing_ok=True)
            if created_dir:
                try:
                    frontend.rmdir()
                except OSError:
                    pass
        # clean-state must be green again
        res2 = subprocess.run([sys.executable, str(self.SCRIPT)], cwd=ROOT,
                              capture_output=True, text=True, timeout=120)
        self.assertEqual(res2.returncode, 0, res2.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
