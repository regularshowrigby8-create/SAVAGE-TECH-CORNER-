"""Minimal GitHub client (REST v3) with ETag caching and rate-limit awareness.

Used by the collector scripts and the self-improvement loop. Only the endpoints
this tool actually needs are implemented — a smaller surface is easier to verify.

Auth: ``GITHUB_TOKEN`` in the environment (or ``[github].token`` in nova.toml).
Unauthenticated calls work but are limited to 60/hour, so the client reports the
remaining budget rather than silently failing.
"""
from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional

API = "https://api.github.com"


class GitHubError(RuntimeError):
    def __init__(self, message: str, status: Optional[int] = None):
        super().__init__(message)
        self.status = status


@dataclass
class Repo:
    full_name: str
    description: str = ""
    stars: int = 0
    language: Optional[str] = None
    topics: List[str] = field(default_factory=list)
    url: str = ""
    updated_at: str = ""

    def as_dict(self) -> Dict[str, Any]:
        return {
            "full_name": self.full_name,
            "description": self.description,
            "stars": self.stars,
            "language": self.language,
            "topics": self.topics,
            "url": self.url,
            "updated_at": self.updated_at,
        }


def parse_repo(raw: Dict[str, Any]) -> Optional[Repo]:
    if not isinstance(raw, dict) or not raw.get("full_name"):
        return None
    return Repo(
        full_name=raw["full_name"],
        description=(raw.get("description") or "")[:500],
        stars=int(raw.get("stargazers_count") or 0),
        language=raw.get("language"),
        topics=[str(t) for t in (raw.get("topics") or [])][:20],
        url=raw.get("html_url") or "",
        updated_at=raw.get("updated_at") or "",
    )


class GitHub:
    def __init__(self, token: Optional[str] = None, user_agent: str = "NovaTV/1.0", timeout: float = 20.0):
        self.token = token
        self.user_agent = user_agent
        self.timeout = timeout
        self.remaining: Optional[int] = None
        self._etags: Dict[str, tuple[str, Any]] = {}

    def _headers(self) -> Dict[str, str]:
        headers = {
            "User-Agent": self.user_agent,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return headers

    def get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        url = API + path
        if params:
            url += "?" + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        headers = self._headers()
        cached = self._etags.get(url)
        if cached:
            headers["If-None-Match"] = cached[0]
        request = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as handle:
                self._track(handle.headers)
                etag = handle.headers.get("ETag")
                payload = json.loads(handle.read().decode("utf-8", "replace"))
                if etag:
                    self._etags[url] = (etag, payload)
                return payload
        except urllib.error.HTTPError as error:
            if error.code == 304 and cached:
                return cached[1]
            self._track(error.headers)
            if error.code == 401:
                raise GitHubError("bad credentials — check GITHUB_TOKEN", 401) from error
            if error.code == 403 and self.remaining == 0:
                raise GitHubError("rate limit exhausted; set GITHUB_TOKEN", 403) from error
            raise GitHubError(f"HTTP {error.code} for {path}", error.code) from error
        except urllib.error.URLError as error:
            raise GitHubError(f"network error for {path}: {error}") from error

    def _track(self, headers) -> None:
        try:
            self.remaining = int(headers.get("X-RateLimit-Remaining"))
        except (TypeError, ValueError):
            pass

    def search_repos(self, query: str, *, limit: int = 30, sort: str = "stars") -> List[Repo]:
        payload = self.get("/search/repositories", {"q": query, "sort": sort, "order": "desc", "per_page": min(100, limit)})
        items = payload.get("items") if isinstance(payload, dict) else None
        if not isinstance(items, list):
            raise GitHubError("search response had no items")
        repos = [parse_repo(item) for item in items]
        return [repo for repo in repos if repo is not None]

    def repo(self, full_name: str) -> Repo:
        parsed = parse_repo(self.get(f"/repos/{full_name}"))
        if parsed is None:
            raise GitHubError(f"unexpected repo payload for {full_name}")
        return parsed

    def tree(self, full_name: str, ref: str = "HEAD", recursive: bool = True) -> List[Dict[str, Any]]:
        payload = self.get(f"/repos/{full_name}/git/trees/{ref}", {"recursive": "1" if recursive else None})
        tree = payload.get("tree") if isinstance(payload, dict) else None
        if not isinstance(tree, list):
            raise GitHubError(f"no tree for {full_name}")
        return tree

    def file_text(self, full_name: str, path: str, ref: str = "HEAD") -> str:
        payload = self.get(f"/repos/{full_name}/contents/{urllib.parse.quote(path)}", {"ref": ref})
        if isinstance(payload, dict) and payload.get("content"):
            import base64
            return base64.b64decode(payload["content"]).decode("utf-8", "replace")
        raise GitHubError(f"{path} is not a readable file in {full_name}")

    def rate_limit(self) -> Dict[str, Any]:
        return self.get("/rate_limit")


def code_paths(tree: Iterable[Dict[str, Any]], *, extensions: Iterable[str] = (".kt", ".py", ".ts")) -> List[str]:
    """Filter a git tree to source files, excluding generated/vendor trees."""
    wanted = tuple(extensions)
    skip = ("node_modules/", "build/", "dist/", ".gradle/", "vendor/", "third_party/")
    paths = []
    for entry in tree:
        path = entry.get("path") or ""
        if entry.get("type") != "blob":
            continue
        if not path.endswith(wanted):
            continue
        if any(part in path for part in skip):
            continue
        paths.append(path)
    return sorted(paths)
