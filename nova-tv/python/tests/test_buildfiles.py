"""The Gradle build file must keep the declarations AGP reads first.

A splice that deleted `compileSdk` and `defaultConfig` produced a build that
failed on line one with "compileSdkVersion is not specified" -- a message that
names the symptom and not the cause. This test pins the declarations so the next
accidental deletion fails in the test suite, in one second, instead of in CI.
"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
LINT = ROOT / "ops" / "kotlin_lint.py"

MODULE = """android {
    namespace = "dev.nova.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.nova.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes { }
}
"""


def project(root: Path, module_text: str) -> Path:
    android = root / "android"
    (android / "gradle").mkdir(parents=True)
    (android / "gradle" / "libs.versions.toml").write_text("[versions]\n")
    (android / "app").mkdir(parents=True)
    (android / "app" / "build.gradle.kts").write_text(module_text)
    return root


def check(root: Path) -> list[str]:
    """Call the build-file checker directly.

    Scraping stdout was wrong: the CLI prints a *count* of build-file issues, not
    the messages, so the first version of this test asserted on a line that never
    contained them and failed while the checker was working correctly.
    """
    import importlib.util

    spec = importlib.util.spec_from_file_location("kotlin_lint", LINT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    # Filter to the declaration guard. `check_build_files` also validates
    # namespace-vs-source-layout and the version catalog, which a minimal fixture
    # has no reason to satisfy.
    return [p for p in module.check_build_files(root) if "is not declared" in p]


class BuildFileTest(unittest.TestCase):
    def test_complete_module_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = project(Path(tmp), MODULE)
            self.assertEqual(check(root), [])

    def test_missing_compile_sdk_is_caught(self):
        with tempfile.TemporaryDirectory() as tmp:
            broken = MODULE.replace('    compileSdk = 35\n', "")
            root = project(Path(tmp), broken)
            problems = check(root)
            self.assertTrue(any("compileSdk" in p for p in problems), problems)

    def test_missing_default_config_is_caught(self):
        with tempfile.TemporaryDirectory() as tmp:
            start = MODULE.index("    defaultConfig {")
            end = MODULE.index("    buildTypes {")
            root = project(Path(tmp), MODULE[:start] + MODULE[end:])
            problems = check(root)
            self.assertTrue(any("defaultConfig" in p for p in problems), problems)

    def test_missing_application_id_is_caught(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = project(Path(tmp), MODULE.replace('applicationId = "dev.nova.tv"', ""))
            problems = check(root)
            self.assertTrue(any("applicationId" in p for p in problems), problems)


if __name__ == "__main__":
    unittest.main()
