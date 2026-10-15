#!/usr/bin/env python3
"""Prints the failing instrumented-test cases from the JUnit XML Gradle wrote.

When ``connectedDebugAndroidTest`` fails, the interesting part is *which* test failed and why. The
Gradle log is long and, on a hosted runner, is not always retrievable afterwards, so this reporter
turns the result XML into a short, greppable block that CI can attach to the job.

usage:
    python3 tools/androidtest_failure_report.py [results-dir ...]

Exit codes:
    0 - a report was printed (failures may or may not be present; the summary says which)
    3 - no result XML was found, so nothing can be said about the run
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_RESULT_DIRS = (
    ROOT_DIR / "shell" / "android_project" / "app" / "build" / "outputs" / "androidTest-results",
    ROOT_DIR / "shell" / "android_project" / "app" / "build" / "reports" / "androidTests",
)
MESSAGE_LIMIT = 600


def summarize_message(element: ElementTree.Element) -> str:
    """One line of the failure message, with the useless class-file prefix stripped."""
    text = (element.get("message") or element.text or "").strip()
    if not text:
        return "<no message>"
    first_line = text.splitlines()[0].strip()
    return first_line[:MESSAGE_LIMIT]


def collect(result_dirs) -> tuple:
    """Returns (failures, totals) read from every JUnit XML under the given directories."""
    failures = []
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0, "suites": 0}
    for directory in result_dirs:
        directory = Path(directory)
        if not directory.is_dir():
            continue
        for xml_path in sorted(directory.rglob("*.xml")):
            try:
                root = ElementTree.parse(xml_path).getroot()
            except ElementTree.ParseError as error:
                failures.append((str(xml_path), "<unreadable result file>", str(error)))
                continue
            if root.tag != "testsuite":
                continue
            totals["suites"] += 1
            for key in ("tests", "failures", "errors", "skipped"):
                totals[key] += int(root.get(key, "0") or 0)
            for case in root.iter("testcase"):
                for kind in ("failure", "error"):
                    problem = case.find(kind)
                    if problem is None:
                        continue
                    name = f"{case.get('classname', '?')}#{case.get('name', '?')}"
                    failures.append((str(xml_path), name, summarize_message(problem)))
    return failures, totals


def main() -> int:
    arguments = sys.argv[1:]
    result_dirs = [Path(a) for a in arguments] if arguments else list(DEFAULT_RESULT_DIRS)
    failures, totals = collect(result_dirs)

    print("=== instrumented test report ===")
    print(f"result directories: {', '.join(str(d) for d in result_dirs)}")
    print(f"suites={totals['suites']} tests={totals['tests']} failures={totals['failures']} "
          f"errors={totals['errors']} skipped={totals['skipped']}")
    if totals["suites"] == 0:
        print("FAIL: no instrumented test results were found - the run produced no evidence")
        return 3
    if not failures:
        print("every instrumented test case passed")
        return 0
    for path, name, message in failures:
        print(f"FAILED {name}")
        print(f"    {message}")
        print(f"    in {path}")
    print(f"=== {len(failures)} failing test case(s) ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
