"""Directive lock: "delete all APK versions, keep the architecture".

These tests hard-assert the invariant so it can never silently regress:
  1. No APK/AAB binary is tracked by git.
  2. .gitignore keeps APK/AAB artifacts out of version control.
  3. Deprecated binary-shipping tools fail closed (clear error), never silently pass.
  4. The retained source architecture still passes its own verification gates.
  5. CI must not commit built APKs back into the repository.
"""

from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent


def _git_ls_files() -> list[str]:
    res = subprocess.run(
        ["git", "ls-files"], capture_output=True, text=True, cwd=ROOT, check=True
    )
    return [ln.strip() for ln in res.stdout.splitlines() if ln.strip()]


class TestApkRemovalContract(unittest.TestCase):
    def test_no_apk_or_aab_tracked_by_git(self) -> None:
        tracked = _git_ls_files()
        offenders = [f for f in tracked if f.lower().endswith((".apk", ".aab"))]
        self.assertEqual(offenders, [], f"APK/AAB binaries must not be tracked: {offenders}")

    def test_gitignore_blocks_apk_artifacts(self) -> None:
        gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        lines = {ln.strip() for ln in gitignore.splitlines()}
        self.assertIn("*.apk", lines, ".gitignore must ignore *.apk")
        self.assertIn("*.aab", lines, ".gitignore must ignore *.aab")

    def test_apk_builder_deprecated_fails_closed(self) -> None:
        sys.path.insert(0, str(ROOT / "factories" / "factory-3" / "tools"))
        import apk_builder

        for entry in ("create_signed_apk", "build_signed_apk", "inject_signing_block", "main"):
            fn = getattr(apk_builder, entry)
            with self.assertRaises(NotImplementedError, msg=f"{entry} must fail closed"):
                fn(Path("/tmp/nothing.apk"))

    def test_browser_rules_apk_audit_deprecated_source_gate_green(self) -> None:
        sys.path.insert(0, str(ROOT / "factories" / "factory-3" / "tools"))
        import browser_app_rules

        with self.assertRaises(NotImplementedError):
            browser_app_rules.verify_browser_app_rules("anything.apk")
        res = browser_app_rules.verify_browser_source_rules()
        self.assertTrue(res["passed"], f"source architecture gate must stay green: {res['violations']}")

    def test_rbv_source_gate_passes_without_any_apk(self) -> None:
        import importlib.util

        spec = importlib.util.spec_from_file_location(
            "reproducible_build_verifier", ROOT / "tools" / "reproducible_build_verifier.py"
        )
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        errors = mod.verify_android_source_tree()
        self.assertEqual(errors, [], f"source-tree gate must pass: {errors}")

    def test_ci_does_not_commit_apks_back(self) -> None:
        wf = (ROOT / ".github" / "workflows" / "android-build.yml").read_text(encoding="utf-8")
        self.assertNotIn(
            "git add \"assets/SavageBrowser",
            wf,
            "CI must never 'git add' an APK back into the repository",
        )
        self.assertNotIn(
            "Sync Verified APK Into Repository Assets",
            wf,
            "the commit-back-to-repo step must stay removed",
        )

    def test_android_source_architecture_retained(self) -> None:
        proj = ROOT / "shell" / "android_project"
        for rel in (
            "settings.gradle.kts",
            "build.gradle.kts",
            "app/build.gradle.kts",
            "app/src/main/AndroidManifest.xml",
            "app/src/main/java/com/savage/soulbrowser/activities/BrowserActivity.java",
            "app/src/main/res/values/strings.xml",
        ):
            self.assertTrue((proj / rel).exists(), f"architecture file missing: {rel}")


if __name__ == "__main__":
    unittest.main()
