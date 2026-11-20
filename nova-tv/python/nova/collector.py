"""Continuous GitHub collection.

Searches a rotating set of queries, keeps a de-duplicated knowledge base, and
records what changed on each pass. The state file makes the loop resumable, so a
repeated run costs almost nothing and the output is a diff rather than a dump.

This is the "continuously debug and fetch code from GitHub" part of the brief:
it discovers reference implementations, fetches the files worth reading, and
keeps an index that the rest of the tooling (and the AI reviewer) can query.
"""
from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

from .github import GitHub, GitHubError, Repo, code_paths

# Queries chosen to cover the architectures this project actually learns from.
DEFAULT_QUERIES = [
    "stremio addon client android",
    "android tv player kotlin exoplayer",
    "android tv leanback streaming app",
    "iptv player android kotlin",
    "torrent streaming android kotlin",
    "exoplayer hls player kotlin",
    "media3 exoplayer androidx sample",
    "stremio addon server",
    "streaming catalogue provider python",
    "kotlin compose tv app",
]

INTERESTING_EXT = (".kt", ".kts", ".java", ".py", ".ts", ".json", ".xml")


@dataclass
class KnowledgeBase:
    repos: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    seen_queries: Dict[str, float] = field(default_factory=dict)
    last_run: float = 0.0

    @classmethod
    def load(cls, path: Path) -> "KnowledgeBase":
        if not path.exists():
            return cls()
        try:
            data = json.loads(path.read_text("utf-8"))
        except (OSError, ValueError):
            return cls()
        return cls(
            repos=data.get("repos") or {},
            seen_queries=data.get("seen_queries") or {},
            last_run=float(data.get("last_run") or 0),
        )

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(
                {"repos": self.repos, "seen_queries": self.seen_queries, "last_run": self.last_run},
                indent=2,
                sort_keys=True,
            ),
            "utf-8",
        )

    def add(self, repo: Repo, query: str) -> bool:
        """Add a repo; returns True when it is new."""
        if repo.full_name in self.repos:
            entry = self.repos[repo.full_name]
            entry["stars"] = max(entry.get("stars", 0), repo.stars)
            entry["queries"] = sorted(set(entry.get("queries", [])) | {query})
            return False
        self.repos[repo.full_name] = {**repo.as_dict(), "queries": [query], "discovered_at": time.time()}
        return True

    def search(self, needle: str) -> List[Dict[str, Any]]:
        needle = needle.lower()
        hits = []
        for entry in self.repos.values():
            haystack = " ".join(
                [entry.get("full_name", ""), entry.get("description", ""), " ".join(entry.get("topics", []))]
            ).lower()
            if needle in haystack:
                hits.append(entry)
        return sorted(hits, key=lambda e: e.get("stars", 0), reverse=True)

    def by_language(self) -> Dict[str, int]:
        counts: Dict[str, int] = {}
        for entry in self.repos.values():
            language = entry.get("language") or "unknown"
            counts[language] = counts.get(language, 0) + 1
        return dict(sorted(counts.items(), key=lambda kv: kv[1], reverse=True))

    def to_dict(self) -> Dict[str, Any]:
        return {
            "count": len(self.repos),
            "languages": self.by_language(),
            "repos": self.repos,
            "last_run": self.last_run,
        }


def rotate_queries(queries: List[str], seen: Dict[str, float], budget: int) -> List[str]:
    """Pick the queries that have waited longest, so every one gets a turn."""
    ordered = sorted(queries, key=lambda q: seen.get(q, 0))
    return ordered[: max(1, budget)]


def collect(
    gh: GitHub,
    queries: List[str],
    kb: KnowledgeBase,
    *,
    per_query: int = 10,
    budget: int = 4,
) -> Dict[str, Any]:
    """One collection pass. Returns a report of what was discovered."""
    chosen = rotate_queries(queries, kb.seen_queries, budget)
    discovered: List[str] = []
    errors: List[str] = []
    for query in chosen:
        try:
            for repo in gh.search_repos(query, limit=per_query):
                if kb.add(repo, query):
                    discovered.append(repo.full_name)
        except GitHubError as error:
            errors.append(f"{query}: {error}")
        kb.seen_queries[query] = time.time()
    kb.last_run = time.time()
    return {
        "queries_run": chosen,
        "discovered": discovered,
        "new_count": len(discovered),
        "total_known": len(kb.repos),
        "errors": errors,
    }


def snapshot_repo(gh: GitHub, full_name: str, *, max_files: int = 12) -> Dict[str, Any]:
    """Fetch a repo's shape and the head of its most relevant source files."""
    tree = gh.tree(full_name)
    paths = code_paths(tree, extensions=INTERESTING_EXT)
    files: List[Dict[str, Any]] = []
    for path in paths[:max_files]:
        try:
            text = gh.file_text(full_name, path)
        except GitHubError:
            continue
        files.append(
            {
                "path": path,
                "bytes": len(text),
                "sha": hashlib.sha256(text.encode("utf-8")).hexdigest()[:16],
                "head": text[:4000],
            }
        )
    return {"full_name": full_name, "file_count": len(paths), "files": files}


def diff_snapshots(before: Dict[str, Any], after: Dict[str, Any]) -> Dict[str, List[str]]:
    """What changed between two snapshots of the same repo."""
    old = {f["path"]: f["sha"] for f in before.get("files", [])}
    new = {f["path"]: f["sha"] for f in after.get("files", [])}
    return {
        "added": sorted(set(new) - set(old)),
        "removed": sorted(set(old) - set(new)),
        "changed": sorted(path for path in set(old) & set(new) if old[path] != new[path]),
    }
