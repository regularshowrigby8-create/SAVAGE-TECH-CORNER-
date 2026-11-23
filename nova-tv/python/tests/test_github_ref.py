"""The ref must be explicit: GitHub resolves HEAD to the default branch."""
from __future__ import annotations

import unittest
from unittest import mock

from nova.github import GitHub


class RefTest(unittest.TestCase):
    def test_constructor_carries_the_ref(self):
        gh = GitHub("t", "ua", 1.0, "arena/some-branch")
        self.assertEqual(gh.ref, "arena/some-branch")

    def test_default_ref_is_head(self):
        self.assertEqual(GitHub().ref, "HEAD")

    def test_file_text_sends_the_configured_ref(self):
        gh = GitHub("t", "ua", 1.0, "arena/branch-x")
        with mock.patch.object(gh, "get", return_value={"content": "aGVsbG8="}) as get:
            gh.file_text("o/r", "some/path.js")
        params = get.call_args[0][1]
        # The bug this pins: omitting the ref makes GitHub serve `main`, so a
        # read of this branch's own files silently returned another branch.
        self.assertEqual(params["ref"], "arena/branch-x")

    def test_explicit_ref_argument_wins(self):
        gh = GitHub("t", "ua", 1.0, "arena/branch-x")
        with mock.patch.object(gh, "get", return_value={"content": "aGVsbG8="}) as get:
            gh.file_text("o/r", "some/path.js", ref="main")
        self.assertEqual(get.call_args[0][1]["ref"], "main")

    def test_tree_sends_the_configured_ref(self):
        gh = GitHub("t", "ua", 1.0, "arena/branch-x")
        with mock.patch.object(gh, "get", return_value={"tree": []}) as get:
            gh.tree("o/r")
        self.assertIn("arena/branch-x", get.call_args[0][0])


if __name__ == "__main__":
    unittest.main()
