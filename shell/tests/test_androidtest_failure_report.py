"""Tests for tools/androidtest_failure_report.py.

The reporter is what turns a red emulator job into a diagnosable record, so its behaviour is pinned:
it must name the failing case, it must say so when it found nothing to read, and the exit code must
never present an empty result directory as a pass.
"""

import importlib.util
import io
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

    def test_the_workflow_never_suppresses_the_test_command(self):
        workflow = io.open(REPO_ROOT / ".github" / "workflows" / "android-build.yml",
                           encoding="utf-8").read()
        for forbidden in ("|| true", "continue-on-error", "|| echo"):
            self.assertNotIn(forbidden, workflow)


if __name__ == "__main__":
    unittest.main(verbosity=2)
