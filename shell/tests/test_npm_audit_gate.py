"""Tests for the dependency vulnerability gate (tools/security/npm_audit_gate.mjs).

The gate replaced `npm audit --audit-level=high`, which cannot pass while the toolchain
carries an advisory with no published fix. That makes the gate's exemptions the interesting
part: an exemption that is too wide silently disables the check, and an exemption list that
is never pruned keeps covering advisories that no longer exist. These tests drive the script
with fixture audit reports so both failure modes are pinned, along with the fail-closed
behaviour when the audit itself cannot run.
"""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
GATE = REPO_ROOT / "tools" / "security" / "npm_audit_gate.mjs"


def run_gate(args):
    return subprocess.run(
        ["node", str(GATE), *args],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        timeout=120,
        check=False,
    )


def accepted_entries():
    result = run_gate(["--print-accepted"])
    if result.returncode != 0:
        raise AssertionError(f"--print-accepted failed: {result.stderr}")
    return json.loads(result.stdout)


def report_for(entries, extra=None):
    """Build an npm audit report that contains exactly the given advisories."""
    vulnerabilities = {}
    for entry in entries:
        vulnerabilities[entry["package"]] = {
            "severity": entry.get("severity", "high"),
            "via": [
                {
                    "source": 1,
                    "name": entry["package"],
                    "dependency": entry["package"],
                    "title": entry.get("title", "fixture advisory"),
                    "url": f"https://github.com/advisories/{entry['id']}",
                    "severity": entry.get("severity", "high"),
                }
            ],
        }
    for entry in extra or []:
        observed = vulnerabilities.setdefault(
            entry["package"], {"severity": entry.get("severity", "high"), "via": []}
        )
        observed["via"].append(
            {
                "source": 2,
                "name": entry["package"],
                "dependency": entry["package"],
                "title": entry.get("title", "fixture advisory"),
                "url": f"https://github.com/advisories/{entry['id']}",
                "severity": entry.get("severity", "high"),
            }
        )
    return {"auditReportVersion": 2, "vulnerabilities": vulnerabilities, "metadata": {}}


class AllowlistIntegrityTest(unittest.TestCase):
    """The allowlist itself has to stay narrow and honest."""

    def test_every_accepted_advisory_carries_a_written_reason(self):
        entries = accepted_entries()
        self.assertTrue(entries, "the gate should expose its accepted advisories")
        for entry in entries:
            with self.subTest(advisory=entry["id"]):
                self.assertIn("package", entry)
                self.assertGreaterEqual(
                    len(entry.get("reason", "")),
                    80,
                    "an accepted advisory needs a reason that explains why it is tolerated",
                )

    def test_accepted_packages_are_not_production_dependencies(self):
        package_json = json.loads((REPO_ROOT / "package.json").read_text(encoding="utf-8"))
        production = set(package_json.get("dependencies", {}))
        for entry in accepted_entries():
            with self.subTest(package=entry["package"]):
                self.assertNotIn(
                    entry["package"],
                    production,
                    "a build-time exemption must never cover a shipped dependency",
                )

    def test_the_npm_script_runs_this_gate(self):
        package_json = json.loads((REPO_ROOT / "package.json").read_text(encoding="utf-8"))
        self.assertIn(
            "npm_audit_gate.mjs",
            package_json["scripts"]["audit:security"],
            "the audit:security script must be the gate, not plain npm audit",
        )
        self.assertNotIn(
            "--audit-level",
            package_json["scripts"]["audit:security"],
            "plain npm audit cannot express a reasoned exemption and always fails here",
        )


class GateBehaviourTest(unittest.TestCase):
    """Fixture-driven checks of what the gate accepts and refuses."""

    def write_report(self, report):
        handle = tempfile.NamedTemporaryFile(
            "w", suffix=".json", delete=False, encoding="utf-8"
        )
        json.dump(report, handle)
        handle.close()
        self.addCleanup(Path(handle.name).unlink)
        return handle.name

    def test_accepted_advisory_passes_and_is_reported(self):
        entries = accepted_entries()
        report = report_for(
            [
                {
                    "id": entries[0]["id"],
                    "package": entries[0]["package"],
                    "severity": "high",
                }
            ]
        )
        result = run_gate(["--audit-json", self.write_report(report)])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("[PASS]", result.stdout)
        self.assertIn(entries[0]["id"], result.stdout)

    def test_unaccepted_high_advisory_fails(self):
        entries = accepted_entries()
        report = report_for(
            [{"id": entries[0]["id"], "package": entries[0]["package"], "severity": "high"}],
            extra=[{"id": "GHSA-0000-0000-0000", "package": "left-pad", "severity": "high"}],
        )
        result = run_gate(["--audit-json", self.write_report(report)])
        self.assertEqual(result.returncode, 1)
        self.assertIn("GHSA-0000-0000-0000", result.stderr)
        self.assertIn("[FAIL]", result.stderr)

    def test_unaccepted_critical_advisory_fails(self):
        entries = accepted_entries()
        report = report_for(
            [{"id": entries[0]["id"], "package": entries[0]["package"], "severity": "high"}],
            extra=[{"id": "GHSA-1111-1111-1111", "package": "tinypool", "severity": "critical"}],
        )
        result = run_gate(["--audit-json", self.write_report(report)])
        self.assertEqual(result.returncode, 1)
        self.assertIn("[FAIL]", result.stderr)

    def test_unaccepted_moderate_advisory_is_noted_not_blocked(self):
        entries = accepted_entries()
        report = report_for(
            [{"id": entries[0]["id"], "package": entries[0]["package"], "severity": "high"}],
            extra=[{"id": "GHSA-2222-2222-2222", "package": "some-dep", "severity": "moderate"}],
        )
        result = run_gate(["--audit-json", self.write_report(report)])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("[NOTE]", result.stdout)
        self.assertIn("GHSA-2222-2222-2222", result.stdout)

    def test_stale_allowlist_entry_fails(self):
        report = report_for([])
        result = run_gate(["--audit-json", self.write_report(report)])
        self.assertEqual(result.returncode, 1)
        self.assertIn("stale", result.stderr)

    def test_audit_error_is_not_treated_as_a_pass(self):
        result = run_gate(["--audit-json", self.write_report({"error": {"code": "EAI_AGAIN"}})])
        self.assertEqual(result.returncode, 1)
        self.assertIn("EAI_AGAIN", result.stderr)

    def test_unreadable_report_is_not_treated_as_a_pass(self):
        handle = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
        handle.write("{not json")
        handle.close()
        self.addCleanup(Path(handle.name).unlink)
        result = run_gate(["--audit-json", handle.name])
        self.assertEqual(result.returncode, 1)
        self.assertIn("[FAIL]", result.stderr)

    def test_missing_report_path_is_a_usage_failure(self):
        result = run_gate(["--audit-json"])
        self.assertEqual(result.returncode, 1)
        self.assertIn("--audit-json needs a file path", result.stderr)


if __name__ == "__main__":
    unittest.main()
