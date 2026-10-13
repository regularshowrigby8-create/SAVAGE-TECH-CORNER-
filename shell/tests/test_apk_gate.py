"""Tests for the APK gate itself (tools/reproducible_build_verifier.py).

A gate that is never tested is a gate that can quietly stop gating. These tests pin the behaviour
that the CI run depends on: the identity read from the real Gradle build file, the permission model
that distinguishes platform permissions from the signature-protected permission the Android Gradle
Plugin generates, and the fail-closed behaviour when this checkout has produced no APK.
"""

import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[2]
VERIFIER_PATH = REPO_ROOT / "tools" / "reproducible_build_verifier.py"


def load_verifier():
    spec = importlib.util.spec_from_file_location("reproducible_build_verifier", VERIFIER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


verifier = load_verifier()


class ProtectionLevelDecodingTest(unittest.TestCase):
    """A compiled manifest stores the bitmask; the gate must decode it, not string-match it."""

    def test_binary_signature_flag_from_a_real_compiled_manifest(self):
        self.assertEqual({"signature"}, verifier.protection_level_names("0x00000002"))
        self.assertTrue(verifier.is_signature_protected("0x00000002"))

    def test_source_text_form_is_understood(self):
        self.assertTrue(verifier.is_signature_protected("signature"))
        self.assertTrue(verifier.is_signature_protected("signature|privileged"))

    def test_other_protection_levels_are_not_mistaken_for_signature(self):
        for raw in ("normal", "0x00000000", "0x00000001", "0x00000008", "0x00000010", None, ""):
            with self.subTest(raw=raw):
                self.assertFalse(verifier.is_signature_protected(raw))

    def test_combination_flags_are_named(self):
        self.assertEqual({"signature", "development"},
                         verifier.protection_level_names("0x00000012"))


class PermissionModelTest(unittest.TestCase):
    """The product needs INTERNET and ACCESS_NETWORK_STATE, and nothing else."""

    APPLICATION_ID = "com.savage.soulbrowser"
    INJECTED = APPLICATION_ID + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    def findings(self, platform, self_declared, levels=None):
        return verifier.permission_findings(self.APPLICATION_ID, sorted(platform),
                                            sorted(self_declared), levels or {})

    def test_the_expected_comment_free_set_passes(self):
        self.assertEqual([], self.findings(
            {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"}, set()))

    def test_the_generated_signature_permission_passes(self):
        self.assertEqual([], self.findings(
            {"android.permission.INTERNET"},
            {self.INJECTED},
            {self.INJECTED: "0x00000002"}))

    def test_any_extra_platform_permission_fails(self):
        findings = self.findings(
            {"android.permission.INTERNET", "android.permission.CAMERA"}, set())
        self.assertEqual(1, len(findings), findings)
        self.assertIn("android.permission.CAMERA", findings[0])

    def test_the_generated_permission_must_really_be_signature_protected(self):
        findings = self.findings({"android.permission.INTERNET"}, {self.INJECTED},
                                 {self.INJECTED: "0x00000001"})
        self.assertEqual(1, len(findings), findings)
        self.assertIn("not signature-protected", findings[0])

    def test_an_undeclared_self_permission_fails(self):
        findings = self.findings({"android.permission.INTERNET"}, {self.INJECTED}, {})
        self.assertEqual(1, len(findings), findings)
        self.assertIn("not declared", findings[0])

    def test_a_foreign_self_permission_fails(self):
        findings = self.findings(
            {"android.permission.INTERNET"},
            {"com.android.browser.permission.READ_HISTORY_BOOKMARKS"},
            {"com.android.browser.permission.READ_HISTORY_BOOKMARKS": "0x00000002"})
        self.assertEqual(1, len(findings), findings)
        self.assertIn("unexpected self-permission", findings[0])


class ExpectedIdentityTest(unittest.TestCase):
    """The expectations come from the build file, so they cannot drift away from the build."""

    def test_identity_matches_the_gradle_build_file(self):
        expected = verifier.read_expected_identity()
        self.assertEqual("com.savage.soulbrowser", expected["application_id"])
        self.assertEqual("3.0.0", expected["version_name"])
        self.assertEqual("5", expected["version_code"])
        self.assertEqual("26", expected["min_sdk"])
        self.assertEqual("35", expected["target_sdk"])

    def test_source_contract_matches_the_manifest(self):
        contract = verifier.read_source_contract()
        self.assertEqual(["android.permission.ACCESS_NETWORK_STATE", "android.permission.INTERNET"],
                         contract["permissions"])
        self.assertEqual(".ui.MainActivity", contract["launcher_activity"])


class FailClosedTest(unittest.TestCase):
    """No APK built by this checkout means no verdict - never a pass."""

    def test_missing_build_output_is_a_system_exit(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake_root = Path(tmp)
            (fake_root / "shell" / "android_project" / "app").mkdir(parents=True)
            with mock.patch.object(verifier, "ROOT_DIR", fake_root):
                with self.assertRaises(SystemExit) as raised:
                    verifier.discover_built_apk()
                self.assertEqual(1, raised.exception.code)

    def test_a_built_apk_is_discovered(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake_root = Path(tmp)
            outputs = fake_root / "shell" / "android_project" / "app" / "build" / "outputs" / "apk"
            outputs.mkdir(parents=True)
            apk = outputs / "app-debug.apk"
            apk.write_bytes(b"not-a-real-apk")
            with mock.patch.object(verifier, "ROOT_DIR", fake_root):
                self.assertEqual(apk.resolve(), verifier.discover_built_apk())

    def test_a_non_zip_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "broken.apk"
            path.write_bytes(b"this is not a zip archive")
            problems = verifier.check_zip(path)
            self.assertTrue(problems)
            self.assertIn("not a valid ZIP archive", problems[0])

    def test_a_zip_without_dex_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "empty.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"x")
                archive.writestr("resources.arsc", b"x")
            problems = verifier.check_zip(path)
            self.assertEqual(1, len(problems), problems)
            self.assertIn("classes*.dex", problems[0])


class FailClosedExitCodeTest(unittest.TestCase):
    """Run the gate the way CI runs it: the exit code and the printed verdict must agree."""

    def run_gate(self, *arguments):
        return subprocess.run(
            [sys.executable, str(VERIFIER_PATH), *arguments],
            capture_output=True, text=True, check=False)

    def test_a_bogus_apk_fails_with_a_nonzero_exit_code(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "fake.apk"
            path.write_bytes(b"this is not an APK")
            result = self.run_gate("--apk", str(path))
            output = result.stdout + result.stderr
            self.assertEqual(1, result.returncode, output)
            self.assertIn("[APK-GATE] FAIL", output)
            self.assertNotIn("[APK-GATE] PASS", output)

    def test_a_missing_apk_fails_with_a_nonzero_exit_code(self):
        result = self.run_gate("--apk", str(Path(tempfile.gettempdir()) / "does-not-exist.apk"))
        output = result.stdout + result.stderr
        self.assertEqual(1, result.returncode, output)
        self.assertIn("does not exist", output)

    def test_only_this_checkouts_build_output_counts_as_provenance(self):
        """A committed APK elsewhere in the tree must not be picked up as build evidence."""
        with tempfile.TemporaryDirectory() as tmp:
            fake_root = Path(tmp)
            prebuilt = fake_root / "artifacts" / "debug" / "SavageBrowser-debug.apk"
            prebuilt.parent.mkdir(parents=True)
            prebuilt.write_bytes(b"a stale APK that this checkout did not build")
            with mock.patch.object(verifier, "ROOT_DIR", fake_root):
                with self.assertRaises(SystemExit) as raised:
                    verifier.discover_built_apk()
                self.assertEqual(1, raised.exception.code)


if __name__ == "__main__":
    unittest.main(verbosity=2)
