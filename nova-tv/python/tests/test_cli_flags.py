"""A tool's parameters must be reachable from the command line.

The bug this pins: twenty tools declared `query`, `repo`, `budget`, `claim` and
so on, and not one of those values could be supplied. The parameters existed,
the flags did not, and the CLI silently ran with defaults. Nothing failed --
which is why it survived.
"""
from __future__ import annotations

import ast
import pathlib
import unittest

from nova.cli import _flags_for, build_parser
from nova.registry import Registry

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent / "scripts"


def tool_functions():
    """Every (file, function node) registered as a tool in scripts/."""
    for path in sorted(SCRIPTS.glob("*.py")):
        tree = ast.parse(path.read_text())
        for node in ast.walk(tree):
            if not isinstance(node, ast.FunctionDef):
                continue
            if not any("REGISTRY" in ast.unparse(d) for d in node.decorator_list):
                continue
            yield path, node


class DerivedFlagTest(unittest.TestCase):
    def test_plain_int_parameter_becomes_typed_flag(self):
        def tool(limit=10):
            return limit

        flags = _flags_for(tool)
        self.assertEqual(flags[0][0], ("--limit",))
        self.assertEqual(flags[0][1]["default"], 10)
        # Without the type, argparse hands the tool the string "10".
        self.assertIs(flags[0][1].get("type"), int)

    def test_float_parameter(self):
        def tool(timeout=0.5):
            return timeout

        self.assertIs(_flags_for(tool)[0][1].get("type"), float)

    def test_string_parameter_has_no_type_coercion(self):
        def tool(query="stremio"):
            return query

        self.assertNotIn("type", _flags_for(tool)[0][1])

    def test_bool_parameter_is_a_switch(self):
        def tool(force=False):
            return force

        info = _flags_for(tool)[0][1]
        self.assertEqual(info["action"], "store_true")
        self.assertFalse(info["default"])

    def test_parameter_without_default_is_required(self):
        def tool(prompt):
            return prompt

        self.assertTrue(_flags_for(tool)[0][1]["required"])

    def test_settings_is_never_a_flag(self):
        def tool(settings=None):
            return settings

        self.assertEqual(_flags_for(tool), [])

    def test_underscores_become_hyphens(self):
        def tool(diff_path=None):
            return diff_path

        self.assertEqual(_flags_for(tool)[0][0], ("--diff-path",))

    def test_explicit_flags_win(self):
        def tool(value=1):
            return value

        tool.__nova_flags__ = [(("--custom",), {"default": 1})]  # type: ignore[attr-defined]
        self.assertEqual(_flags_for(tool)[0][0], ("--custom",))


class EveryScriptFlagTest(unittest.TestCase):
    def test_no_tool_parameter_is_unreachable(self):
        """The regression guard: all 20 tools, checked by parsing, not by trusting."""
        parser_cache: dict[str, object] = {}
        offenders = []
        for path, node in tool_functions():
            params = [a.arg for a in node.args.args if a.arg != "settings"]
            if not params:
                continue
            # Build the parser the same way main() does.
            from nova.cli import main as cli_main  # noqa: F401  (import check)
            source = path.read_text()
            registry = Registry()

            # Execute the script's registry to get the real tool object.
            namespace: dict = {"__name__": "test_import", "__file__": str(path)}
            try:
                exec(compile(source, str(path), "exec"), namespace)
            except Exception as error:  # pragma: no cover - import failure is its own bug
                offenders.append(f"{path.name}: failed to import ({error})")
                continue
            registry = namespace.get("REGISTRY")
            if registry is None:
                continue
            parser = build_parser(path.name, "test", registry)
            # Help output is what a user sees; the flag must appear there.
            import contextlib
            import io
            import sys

            buffer = io.StringIO()
            try:
                with contextlib.redirect_stdout(buffer):
                    with contextlib.redirect_stderr(buffer):
                        try:
                            parser.parse_args([node.name, "--help"])
                        except SystemExit:
                            pass
            finally:
                sys.stdout, sys.stderr = sys.__stdout__, sys.__stderr__
            help_text = buffer.getvalue()
            missing = [p for p in params if f"--{p.replace('_', '-')}" not in help_text]
            if missing:
                offenders.append(f"{path.name}:{node.name} -> {missing}")
        self.assertEqual(offenders, [], "\n".join(offenders))


if __name__ == "__main__":
    unittest.main()
