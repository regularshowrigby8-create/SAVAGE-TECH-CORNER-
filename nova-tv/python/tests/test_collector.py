"""Tests for the collector — no network, deterministic."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nova.collector import KnowledgeBase, diff_snapshots, rotate_queries, snapshot_repo  # noqa: E402
from nova.github import Repo  # noqa: E402


class FakeGitHub:
    def __init__(self, repos_by_query=None, tree=None, files=None):
        self.repos_by_query = repos_by_query or {}
        self._tree = tree or []
        self._files = files or {}
        self.queries = []

    def search_repos(self, query, limit=30, sort="stars"):
        self.queries.append(query)
        return self.repos_by_query.get(query, [])

    def tree(self, full_name, ref="HEAD", recursive=True):
        return self._tree

    def file_text(self, full_name, path, ref="HEAD"):
        return self._files[path]


def repo(name, stars=1):
    return Repo(full_name=name, stars=stars, language="Kotlin")


class TestKnowledgeBase(unittest.TestCase):
    def test_add_is_idempotent_and_merges_queries(self):
        kb = KnowledgeBase()
        self.assertTrue(kb.add(repo("a/b"), "q1"))
        self.assertFalse(kb.add(repo("a/b", stars=9), "q2"))
        self.assertEqual(list(kb.repos), ["a/b"])
        self.assertEqual(kb.repos["a/b"]["stars"], 9)
        self.assertEqual(kb.repos["a/b"]["queries"], ["q1", "q2"])

    def test_roundtrip_persistence(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "kb.json"
            kb = KnowledgeBase()
            kb.add(repo("x/y"), "q")
            kb.save(path)
            again = KnowledgeBase.load(path)
            self.assertEqual(list(again.repos), ["x/y"])

    def test_missing_file_yields_empty(self):
        self.assertEqual(KnowledgeBase.load(Path("/nonexistent/kb.json")).repos, {})

    def test_corrupt_file_yields_empty(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "kb.json"
            path.write_text("{not json", "utf-8")
            self.assertEqual(KnowledgeBase.load(path).repos, {})

    def test_search_matches_name_and_topic(self):
        kb = KnowledgeBase()
        kb.add(Repo(full_name="a/player", description="android tv", stars=5, topics=["kotlin"]), "q")
        self.assertEqual(len(kb.search("player")), 1)
        self.assertEqual(len(kb.search("kotlin")), 1)
        self.assertEqual(len(kb.search("nope")), 0)

    def test_by_language_counts(self):
        kb = KnowledgeBase()
        kb.add(Repo(full_name="a/1", language="Kotlin"), "q")
        kb.add(Repo(full_name="a/2", language="Kotlin"), "q")
        kb.add(Repo(full_name="a/3", language="Python"), "q")
        self.assertEqual(kb.by_language(), {"Kotlin": 2, "Python": 1})


class TestRotation(unittest.TestCase):
    def test_picks_least_recently_used(self):
        queries = ["a", "b", "c"]
        seen = {"a": 300.0, "b": 100.0, "c": 200.0}
        self.assertEqual(rotate_queries(queries, seen, 2), ["b", "c"])

    def test_always_runs_at_least_one(self):
        self.assertEqual(len(rotate_queries(["a"], {}, 0)), 1)


class TestCollect(unittest.TestCase):
    def test_collect_reports_new_repos(self):
        from nova.collector import collect

        fake = FakeGitHub({"q1": [repo("a/b"), repo("c/d")]})
        kb = KnowledgeBase()
        report = collect(fake, ["q1"], kb, budget=1)
        self.assertEqual(sorted(report["discovered"]), ["a/b", "c/d"])
        self.assertEqual(report["total_known"], 2)
        self.assertEqual(report["errors"], [])

    def test_collect_isolates_a_failing_query(self):
        from nova.collector import collect
        from nova.github import GitHubError

        class Flaky(FakeGitHub):
            def search_repos(self, query, limit=30, sort="stars"):
                if query == "bad":
                    raise GitHubError("boom")
                return [repo("ok/repo")]

        kb = KnowledgeBase()
        report = collect(Flaky(), ["bad", "good"], kb, budget=2)
        self.assertEqual(len(report["errors"]), 1)
        self.assertEqual(report["discovered"], ["ok/repo"])

    def test_second_pass_discovers_nothing_new(self):
        from nova.collector import collect

        fake = FakeGitHub({"q1": [repo("a/b")]})
        kb = KnowledgeBase()
        collect(fake, ["q1"], kb, budget=1)
        report = collect(fake, ["q1"], kb, budget=1)
        self.assertEqual(report["new_count"], 0)


class TestSnapshot(unittest.TestCase):
    def test_snapshot_skips_nodes_and_vendor(self):
        tree = [
            {"path": "app/Main.kt", "type": "blob"},
            {"path": "node_modules/x.js", "type": "blob"},
            {"path": "build/Generated.kt", "type": "blob"},
            {"path": "app", "type": "tree"},
        ]
        fake = FakeGitHub(tree=tree, files={"app/Main.kt": "class Main"})
        snap = snapshot_repo(fake, "a/b")
        self.assertEqual([f["path"] for f in snap["files"]], ["app/Main.kt"])
        self.assertEqual(snap["files"][0]["head"], "class Main")

    def test_diff_snapshots(self):
        before = {"files": [{"path": "a.kt", "sha": "1"}, {"path": "b.kt", "sha": "2"}]}
        after = {"files": [{"path": "a.kt", "sha": "9"}, {"path": "c.kt", "sha": "3"}]}
        self.assertEqual(
            diff_snapshots(before, after),
            {"added": ["c.kt"], "removed": ["b.kt"], "changed": ["a.kt"]},
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
