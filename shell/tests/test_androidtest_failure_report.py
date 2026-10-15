"""Tests for tools/androidtest_failure_report.py.

The reporter is what turns a red emulator job into a diagnosable record, so its behaviour is pinned:
it must name the failing case, it must say so when it found nothing to read, and the exit code must
never present an empty result directory as a pass.
"""

import importlib.util
import io
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
REPORTER_PATH = REPO_ROOT / "tools" / "androidtest_failure_report.py"

SUITE_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="{name}" tests="{tests}" failures="{failures}" errors="{errors}" skipped="0">
{body}
</testsuite>
"""

PASSING_CASE = """  <testcase classname="com.savage.soulbrowser.OkTest" name="passes" time="0.1" />"""

FAILING_CASE = """  <testcase classname="com.savage.soulbrowser.BrokenTest" name="fails" time="0.2">
    <failure message="java.lang.AssertionError: expected the tab strip to show 2 tabs&#10;&#9;at BrokenTest.fails(BrokenTest.java:42)">
java.lang.AssertionError: expected the tab strip to show 2 tabs
    at BrokenTest.fails(BrokenTest.java:42)
    </failure>
  </testcase>"""

ERROR_CASE = """  <testcase classname="com.savage.soulbrowser.ErrorTest" name="crashes" time="0.3">
    <error message="android.content.res.Resources$NotFoundException: missing string">
android.content.res.Resources$NotFoundException: missing string
    </error>
  </testcase>"""


def load_reporter():
    spec = importlib.util.spec_from_file_location("androidtest_failure_report", REPORTER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


reporter = load_reporter()


class CollectTest(unittest.TestCase):

    def write_suite(self, directory, name, cases, tests, failures, errors):
        directory.mkdir(parents=True, exist_ok=True)
        body = "\n".join(cases)
        (directory / f"{name}.xml").write_text(
            SUITE_TEMPLATE.format(name=name, tests=tests, failures=failures, errors=errors,
                                  body=body),
            encoding="utf-8")

    def test_passing_suites_produce_no_failure_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.write_suite(Path(tmp), "ok", [PASSING_CASE], 1, 0, 0)
            failures, totals = reporter.collect([Path(tmp)])
            self.assertEqual([], failures)
            self.assertEqual(1, totals["tests"])
            self.assertEqual(1, totals["suites"])

    def test_a_failing_case_is_reported_with_its_method_and_message(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.write_suite(Path(tmp), "broken", [PASSING_CASE, FAILING_CASE], 2, 1, 0)
            failures, totals = reporter.collect([Path(tmp)])
            self.assertEqual(1, totals["failures"])
            self.assertEqual(1, len(failures))
            _, name, message = failures[0]
            self.assertEqual("com.savage.soulbrowser.BrokenTest#fails", name)
            self.assertIn("expected the tab strip to show 2 tabs", message)

    def test_an_error_element_counts_too(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.write_suite(Path(tmp), "error", [ERROR_CASE], 1, 0, 1)
            failures, totals = reporter.collect([Path(tmp)])
            self.assertEqual(1, totals["errors"])
            self.assertEqual("com.savage.soulbrowser.ErrorTest#crashes", failures[0][1])

    def test_nested_result_directories_are_searched(self):
        with tempfile.TemporaryDirectory() as tmp:
            nested = Path(tmp) / "connected" / "Pixel_API_35"
            self.write_suite(nested, "deep", [FAILING_CASE], 1, 1, 0)
            failures, _ = reporter.collect([Path(tmp)])
            self.assertEqual(1, len(failures))

    def test_an_unreadable_result_file_is_reported_not_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "truncated.xml").write_text("<testsuite tests=", encoding="utf-8")
            failures, totals = reporter.collect([Path(tmp)])
            self.assertEqual(0, totals["suites"])
            self.assertEqual(1, len(failures))
            self.assertIn("unreadable", failures[0][1])


class ExitCodeTest(unittest.TestCase):

    def run_reporter(self, directory):
        return subprocess.run([sys.executable, str(REPORTER_PATH), str(directory)],
                              capture_output=True, text=True, check=False)

    def test_no_results_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self.run_reporter(tmp)
            self.assertEqual(3, result.returncode, result.stdout)
            self.assertIn("no instrumented test results were found", result.stdout)

    def test_a_passing_run_says_so(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "connected"
            directory.mkdir(parents=True)
            (directory / "results.xml").write_text(
                SUITE_TEMPLATE.format(name="ok", tests=1, failures=0, errors=0,
                                      body=PASSING_CASE), encoding="utf-8")
            result = self.run_reporter(tmp)
            self.assertEqual(0, result.returncode, result.stdout)
            self.assertIn("every instrumented test case passed", result.stdout)

    def test_a_failing_run_names_the_case(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "connected"
            directory.mkdir(parents=True)
            (directory / "results.xml").write_text(
                SUITE_TEMPLATE.format(name="broken", tests=1, failures=1, errors=0,
                                      body=FAILING_CASE), encoding="utf-8")
            result = self.run_reporter(tmp)
            self.assertEqual(0, result.returncode, result.stdout)
            self.assertIn("FAILED com.savage.soulbrowser.BrokenTest#fails", result.stdout)
            self.assertIn("1 failing test case", result.stdout)


class WiringTest(unittest.TestCase):
    """The reporter is only useful if CI actually calls it when the tests fail."""

    def test_the_pipeline_reports_instrumented_failures(self):
        """The reporter is called by the emulator stage, which the workflow invokes."""
        workflow = io.open(REPO_ROOT / ".github" / "workflows" / "android-build.yml",
                           encoding="utf-8").read()
        stage = io.open(REPO_ROOT / "tools" / "ci" / "android_emulator_run.sh",
                        encoding="utf-8").read()
        self.assertIn("bash tools/ci/android_emulator_run.sh", workflow)
        self.assertIn("androidtest_failure_report.py", stage)
        self.assertIn("connectedDebugAndroidTest", stage)
        self.assertIn("android_apk_smoke.sh", stage)

    def test_the_stage_wakes_the_screen_before_it_runs_the_suite(self):
        """The focus-dependent failures on 226b71c were an uninteractive screen, not a code fault.

        Espresso matches the root view that has window focus, so a headless emulator whose screen is
        off and whose keyguard is showing fails every focus-dependent assertion at once. The stage
        must therefore wake the device, dismiss the keyguard and *check* what it observed before the
        suite runs - and it must not claim the screen is ready when it is not.
        """
        stage = io.open(REPO_ROOT / "tools" / "ci" / "android_emulator_run.sh",
                        encoding="utf-8").read()
        self.assertIn("KEYCODE_WAKEUP", stage)
        self.assertIn("wm dismiss-keyguard", stage)
        self.assertIn("mWakefulness=", stage)
        self.assertIn("mCurrentFocus=", stage)
        # The wake call happens before the suite itself (not merely before the mention of it in the
        # header comment), so the state the suite sees is the checked one.
        self.assertLess(stage.index("KEYCODE_WAKEUP"),
                        stage.index("gradlew connectedDebugAndroidTest"))
        # And a screen that never came up is reported rather than silently assumed.
        self.assertIn("::warning title=emulator pre-flight::", stage)

    def test_the_pre_flight_decides_from_what_it_read_back(self):
        """The pre-flight is logic, so it gets run - against a stub adb on PATH - not just grepped.

        Three devices: one that comes up awake and focused, one that answers Awake but is still on the
        keyguard, and one whose screen stays asleep. The first must be reported ready, the others must
        emit the `::warning` annotation, and none of them may abort the stage.
        """
        stage = (REPO_ROOT / "tools" / "ci" / "android_emulator_run.sh").read_text(encoding="utf-8")
        block = re.search(r"^emulator_preflight\(\) \{.*?^\}\n", stage, re.S | re.M)
        self.assertIsNotNone(block, "the pre-flight must stay a function a test can run")

        def run_with(wakefulness: str, focus: str) -> tuple[int, str]:
            with tempfile.TemporaryDirectory() as where:
                adb = Path(where) / "adb"
                adb.write_text(
                    "#!/usr/bin/env bash\n"
                    "case \"$*\" in\n"
                    f"  *\"dumpsys power\"*) echo \"  mWakefulness={wakefulness}\"; exit 0 ;;\n"
                    f"  *\"dumpsys window\"*) echo \"  mCurrentFocus={focus}\"; exit 0 ;;\n"
                    "  *getprop*) echo 1; exit 0 ;;\n"
                    "  *) exit 0 ;;\n"
                    "esac\n", encoding="utf-8")
                adb.chmod(0o755)
                harness = Path(where) / "harness.sh"
                harness.write_text(
                    "#!/usr/bin/env bash\nset -uo pipefail\nsleep() { :; }\n"
                    f"LOG={where}/log.txt\n" + block.group(0) + "\nemulator_preflight\nexit $?\n",
                    encoding="utf-8")
                done = subprocess.run(["bash", str(harness)], capture_output=True, text=True, timeout=120,
                                      env={**os.environ, "PATH": f"{where}:{os.environ['PATH']}"})
                return done.returncode, done.stdout + done.stderr

        ready_code, ready_output = run_with("Awake", "Window{a b}")
        self.assertEqual(0, ready_code, ready_output)
        self.assertIn("screen awake and focused, keyguard dismissed", ready_output)
        self.assertNotIn("::warning", ready_output)

        for wakefulness, focus, device in (("Awake", "StatusBar{Keyguard}", "awake but on the keyguard"),
                                           ("Asleep", "Window{a b}", "screen not awake")):
            with self.subTest(device=device):
                code, output = run_with(wakefulness, focus)
                self.assertEqual(1, code, output)
                self.assertIn("::warning title=emulator pre-flight::", output)
                self.assertIn("the instrumented suite will run anyway", output)

    def test_the_workflow_never_suppresses_the_test_command(self):
        workflow = io.open(REPO_ROOT / ".github" / "workflows" / "android-build.yml",
                           encoding="utf-8").read()
        for forbidden in ("|| true", "continue-on-error", "|| echo"):
            self.assertNotIn(forbidden, workflow)


if __name__ == "__main__":
    unittest.main(verbosity=2)
