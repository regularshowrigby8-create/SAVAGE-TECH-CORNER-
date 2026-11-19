"""A tool's own verdict must reach the exit status.

The bug this pins: `run()` printed `"ok": true` and returned 0 for every tool
that did not raise, so a check that found problems ("ok": False) looked exactly
like one that passed, both to CI and to anyone reading the JSON.
"""
from __future__ import annotations

import argparse
import contextlib
import io
import json
import unittest

from nova.cli import EXIT_CHECK_FAILED, run
from nova.config import load_settings
from nova.registry import Registry


def _run(result, *, as_json=True):
    registry = Registry()

    @registry.register(summary="test tool", group="verify")
    def tool(settings=None):
        return result

    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = run("tool", argparse.Namespace(json=as_json, verbose=False), registry=registry, settings=load_settings(env={}))
    return code, out.getvalue()


class VerdictTest(unittest.TestCase):
    def test_failing_verdict_exits_nonzero_and_reports_not_ok(self):
        code, out = _run({"ok": False, "findings": ["x"]})
        self.assertEqual(code, EXIT_CHECK_FAILED)
        self.assertIs(json.loads(out)["ok"], False)

    def test_failing_verdict_exits_nonzero_without_json(self):
        code, _ = _run({"ok": False}, as_json=False)
        self.assertEqual(code, EXIT_CHECK_FAILED)

    def test_passing_verdict_exits_zero(self):
        code, out = _run({"ok": True})
        self.assertEqual(code, 0)
        self.assertIs(json.loads(out)["ok"], True)

    def test_result_without_verdict_exits_zero(self):
        self.assertEqual(_run({"rows": 3})[0], 0)
        self.assertEqual(_run(None)[0], 0)

    def test_raising_tool_is_distinct_from_failing_verdict(self):
        registry = Registry()

        @registry.register(summary="boom", group="verify")
        def boom(settings=None):
            raise RuntimeError("boom")

        with contextlib.redirect_stdout(io.StringIO()):
            code = run("boom", argparse.Namespace(json=True, verbose=False), registry=registry, settings=load_settings(env={}))
        self.assertEqual(code, 1)
        self.assertNotEqual(code, EXIT_CHECK_FAILED)


if __name__ == "__main__":
    unittest.main()
