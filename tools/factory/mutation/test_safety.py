"""Mutation scratch suite against an exact copy of the real safety module."""

from pathlib import Path

import pytest
from safety import safe_relative, validate_command


def test_path_and_command_contract():
    """Assert the actual factory safety contract under this test scenario."""
    root = Path("/tmp/factory-mutation-root")
    assert safe_relative(root, "src/check.py") == root / "src/check.py"
    for bad in ("../escape", "/absolute", ".git/config", "a//b", "C:/x", "a\\b"):
        with pytest.raises(ValueError):
            safe_relative(root, bad)
    validate_command(["checker", "--check", "src"])
    for args in (
        ["checker"],
        ["bash", "-c", "echo unsafe"],
        ["checker", "--fix"],
        ["checker", "assembleRelease"],
        ["checker", "--version"],
    ):
        with pytest.raises(ValueError):
            validate_command(args)
