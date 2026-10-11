"""Generative tests exercise real path/argv boundaries, not a demonstration stub."""

import tempfile
from pathlib import Path

import pytest
from hypothesis import given
from hypothesis import strategies as st

from tools.factory.safety import safe_relative, validate_command


@given(
    st.text(alphabet="abcdefghijklmnopqrstuvwxyz0123456789-_", min_size=1, max_size=50)
)
def test_safe_file_stays_inside_repo(name):
    """Assert the actual factory safety contract under this test scenario."""
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        assert safe_relative(root, name).is_relative_to(root)


@given(
    st.sampled_from(["../", "/etc/", ".git/", "a/../../", "C:/", "a\\"]),
    st.text(alphabet="abcdefghijklmnopqrstuvwxyz", min_size=1),
)
def test_escape_and_sensitive_paths_rejected(prefix, name):
    """Assert the actual factory safety contract under this test scenario."""
    with pytest.raises(ValueError):
        safe_relative(Path("/tmp/factory-property-root"), prefix + name)


@given(
    st.sampled_from(["--fix", "--write", "--apply", "assembleRelease", "push", "sign"]),
    st.sampled_from(["", "=true", "=yes"]),
)
def test_mutation_and_release_arguments_rejected(flag, suffix):
    """Assert the actual factory safety contract under this test scenario."""
    with pytest.raises(ValueError):
        validate_command(["checker", flag + suffix])
