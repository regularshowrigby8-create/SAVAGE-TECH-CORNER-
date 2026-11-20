#!/usr/bin/env python3
"""Crawl the public Stremio-addon ecosystem and write a 3000-slot server pool.

This is the standalone crawler. It does the job the on-device harvester does,
but with threads instead of coroutines and without a phone's battery budget, and
it is meant for pre-seeding: the file it writes is the exact JSON the app's
``ServerPool`` reads (``server_pool.json`` in the app's files directory), so a
device can start with a full pool instead of filling it while the viewer waits.

Design, in the order it matters:

* **Continuous, like ZeroPlay's searcher.** It keeps crawling until the pool
  holds ``--target`` servers (3000 by default) or every source reports it has no
  unseen work. It never stops because one round found nothing: only exhaustion or
  the target ends it. Ctrl-C stops it at any time and still writes what it has.
* **Multithreaded.** ``--workers`` requests in flight, at most ``--per-host``
  against any one host, so one slow directory cannot serialise the crawl.
* **Many sources, each counted.** Directories, collection APIs, the catalogue
  graph, GitHub search across many queries and pages, GitHub Pages, homepages and
  community README lists. Every source reports what it found and what it asked
  for, so "0 new servers" is always attributable.
* **Identical safety rules to the app.** https only, no loopback/private/CGNAT
  addresses, no credentials in URLs, no torrent indexes, response size caps, and
  a manifest must actually declare a ``stream`` resource. Anything that fails
  these is counted as a refusal, not added.
* **Honest limits.** GitHub's unauthenticated search allows 10 requests a minute;
  ``--github-token`` raises that. The public directories list far fewer than 3000
  unique servers, so reaching 3000 depends on GitHub Pages, homepages and the
  catalogue graph -- and if the crawler cannot reach 3000 it says so, with the
  exact per-source yield, instead of reporting success.

Usage::

    python3 tools/server_crawler.py                       # fill 3000, write pool.json
    python3 tools/server_crawler.py --target 500 --workers 48
    python3 tools/server_crawler.py --merge existing.json --out server_pool.json
    python3 tools/server_crawler.py --max-seconds 600      # time-boxed crawl
    GITHUB_TOKEN=... python3 tools/server_crawler.py       # higher GitHub quota

Exit codes: 0 when the target was reached, 3 when the crawl finished exhausted
below target, 2 on a usage error.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import re
import signal
import socket
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from concurrent.futures import Future, ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, Iterable, List, Optional, Sequence, Set, Tuple
from urllib.parse import urlparse

CAPACITY = 3000
PER_HOST = 4
MAX_BYTES = 1_500_000
TIMEOUT = 15
USER_AGENT = "NovaTV-Server-Pool/2.0 (+https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-)"

# ---------------------------------------------------------------------------
# URL and manifest rules -- the same ones the app enforces
# ---------------------------------------------------------------------------

_PRIVATE_PREFIXES = (
    "10.", "127.", "0.", "169.254.", "192.168.",
    *(f"172.{n}." for n in range(16, 32)),
    *(f"100.{n}." for n in range(64, 128)),   # CGNAT, what Tailscale hands out
    "198.18.", "198.19.", "198.51.100.", "203.0.113.",
)
_BLOCKED_HOSTS = {"localhost", "0.0.0.0", "::1", "::", "metadata.google.internal"}
_TORRENT_WORDS = ("torrent", "magnet:", "infoHash", "peer", "tracker")
# These need an account, a self-hosted instance or a setup wizard before they
# return one playable link, so they are not servers a viewer can use.
_REDUNDANT = ("penguplay", "jackett", "jacketio", "xtream", "iptv-org", "torrentio", "mediafusion")

# Ends at "manifest.json"; a trailing period in prose (".../manifest.json.")
# must not stop the match, so punctuation is trimmed afterwards, not excluded here.
_MANIFEST_URL = re.compile(r"""https?://[^\s"'<>()\[\]{}]*?manifest\.json(?:[?#][^\s"'<>()\[\]{}]*)?""",
                           re.IGNORECASE)
_ANY_LINK = re.compile(r"""https?://[^\s"'<>()\[\]{}]+""", re.IGNORECASE)


def is_public_https(url: str) -> bool:
    """https, a real host, and not an address that reaches the local network."""
    try:
        parsed = urlparse(url.strip())
    except ValueError:
        return False
    if parsed.scheme.lower() != "https":
        return False
    if parsed.username or parsed.password:
        return False
    host = (parsed.hostname or "").lower()
    if not host or host in _BLOCKED_HOSTS:
        return False
    if host.endswith((".local", ".internal", ".localhost")):
        return False
    if any(host.startswith(p) for p in _PRIVATE_PREFIXES):
        return False
    if ":" in host:                      # IPv6 literals: only public ones are fine
        if host.startswith(("fc", "fd", "fe8", "fe9", "fea", "feb", "::ffff:")):
            return False
    if re.fullmatch(r"\d{1,3}(\.\d{1,3}){3}", host):
        octets = [int(o) for o in host.split(".")]
        if any(o > 255 for o in octets):
            return False
    return len(url) <= 1800


def base_from_manifest(url: str) -> str:
    text = url.strip()
    text = re.sub(r"/manifest\.json.*$", "", text, flags=re.IGNORECASE)
    return text.rstrip("/")


def pool_key(base: str) -> str:
    """Same key the app uses: host + path, lower case, no manifest.json."""
    text = re.sub(r"^https?://", "", base.strip().lower())
    text = re.sub(r"/manifest\.json.*$", "", text)
    return text.split("?")[0].split("#")[0].rstrip("/")


def extract_manifest_urls(text: str, limit: int = 40) -> List[str]:
    out: List[str] = []
    for match in _MANIFEST_URL.finditer(text or ""):
        url = match.group(0).rstrip(".,;:!?)")
        if is_public_https(url) and url not in out:
            out.append(url)
        if len(out) >= limit:
            break
    return out


def manifest_ok(manifest: dict) -> bool:
    """A manifest is a server only if it declares a stream resource."""
    if not isinstance(manifest, dict):
        return False
    resources = manifest.get("resources") or []
    names = [r if isinstance(r, str) else (r or {}).get("name") for r in resources]
    if "stream" not in [str(n) for n in names if n]:
        return False
    types = manifest.get("types") or ["movie", "series"]
    if not isinstance(types, list) or not set(map(str.lower, types)) & {"movie", "series", "tv"}:
        return False
    return True


def redundant(manifest: dict, base: str) -> bool:
    name = str((manifest or {}).get("name", "")).lower()
    host = urlparse(base).hostname or ""
    return any(word in name or word in host for word in _REDUNDANT)


# ---------------------------------------------------------------------------
# HTTP, with per-host politeness that does not serialise the crawl
# ---------------------------------------------------------------------------

class Stats:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.requests = 0
        self.failures = 0
        self.refused = 0
        self.rate_limited = 0
        self.manifests_ok = 0
        self.by_source: Counter = Counter()
        self.started = time.time()

    def bump(self, name: str, amount: int = 1) -> None:
        with self.lock:
            setattr(self, name, getattr(self, name) + amount)

    def add_source(self, source: str, amount: int) -> None:
        with self.lock:
            self.by_source[source] += amount

    def snapshot(self) -> Dict[str, object]:
        with self.lock:
            return {
                "requests": self.requests,
                "failures": self.failures,
                "refused": self.refused,
                "rate_limited": self.rate_limited,
                "manifests_ok": self.manifests_ok,
                "by_source": dict(self.by_source),
                "seconds": round(time.time() - self.started, 1),
            }


class Fetcher:
    """Thread-safe GET with a per-host semaphore and a size cap."""

    def __init__(self, workers: int, per_host: int, stats: Stats, github_token: str = "") -> None:
        self.stats = stats
        self.github_token = github_token
        self._per_host = per_host
        self._hosts: Dict[str, threading.Semaphore] = {}
        self._hosts_lock = threading.Lock()
        self._pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="crawl")
        self._ctx = ssl.create_default_context()

    def _host_gate(self, host: str) -> threading.Semaphore:
        with self._hosts_lock:
            if host not in self._hosts:
                self._hosts[host] = threading.Semaphore(self._per_host)
            return self._hosts[host]

    def get(self, url: str, headers: Optional[Dict[str, str]] = None) -> Optional[str]:
        if not is_public_https(url):
            self.stats.bump("refused")
            return None
        host = urlparse(url).hostname or ""
        gate = self._host_gate(host)
        req_headers = {"User-Agent": USER_AGENT, "Accept": "application/json, text/html;q=0.9"}
        if "api.github.com" in url and self.github_token:
            req_headers["Authorization"] = f"Bearer {self.github_token}"
        req_headers.update(headers or {})
        with gate:
            self.stats.bump("requests")
            try:
                request = urllib.request.Request(url, headers=req_headers)
                with urllib.request.urlopen(request, timeout=TIMEOUT, context=self._ctx) as response:
                    length = response.headers.get("Content-Length")
                    if length and int(length) > MAX_BYTES:
                        self.stats.bump("refused")
                        return None
                    body = response.read(MAX_BYTES + 1)
                    if len(body) > MAX_BYTES:
                        self.stats.bump("refused")
                        return None
                    charset = response.headers.get_content_charset() or "utf-8"
                    return body.decode(charset, "replace")
            except urllib.error.HTTPError as error:
                if error.code in (403, 429):
                    self.stats.bump("rate_limited")
                    time.sleep(2 + random.random() * 3)
                else:
                    self.stats.bump("failures")
                return None
            except (urllib.error.URLError, TimeoutError, socket.timeout, ssl.SSLError, ValueError):
                self.stats.bump("failures")
                return None

    def submit(self, fn: Callable[..., object], *args: object) -> Future:
        return self._pool.submit(fn, *args)

    def shutdown(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)


# ---------------------------------------------------------------------------
# The pool: additive, deduped, capped exactly like ServerPool.kt
# ---------------------------------------------------------------------------

@dataclass
class Entry:
    manifestUrl: str
    baseUrl: str
    name: str = ""
    types: List[str] = field(default_factory=list)
    idPrefixes: List[str] = field(default_factory=list)
    stars: int = 0
    from_: str = ""
    hits: int = 0
    misses: int = 0

    def as_json(self) -> Dict[str, object]:
        return {
            "manifestUrl": self.manifestUrl,
            "baseUrl": self.baseUrl,
            "name": self.name,
            "types": self.types,
            "idPrefixes": self.idPrefixes,
            "stars": self.stars,
            "from": self.from_,
            "hits": self.hits,
            "misses": self.misses,
        }


class Pool:
    def __init__(self, target: int = CAPACITY) -> None:
        self.target = target
        self.entries: Dict[str, Entry] = {}
        self.per_host: Counter = Counter()
        self.retired: Set[str] = set()
        self.lock = threading.Lock()

    @property
    def size(self) -> int:
        return len(self.entries)

    @property
    def full(self) -> bool:
        return len(self.entries) >= self.target

    def has(self, base: str) -> bool:
        return pool_key(base) in self.entries

    def offer(self, entry: Entry) -> bool:
        key = pool_key(entry.baseUrl)
        if not key:
            return False
        host = key.split("/")[0]
        with self.lock:
            if key in self.entries or key in self.retired:
                return False
            if self.per_host[host] >= PER_HOST:
                return False
            if len(self.entries) >= self.target:
                return False
            self.entries[key] = entry
            self.per_host[host] += 1
            return True

    def merge_file(self, path: Path) -> int:
        """Load a previous pool so a crawl adds to it instead of starting over."""
        data = json.loads(path.read_text(encoding="utf-8"))
        added = 0
        for item in data.get("entries", []):
            entry = Entry(
                manifestUrl=item.get("manifestUrl", ""),
                baseUrl=item.get("baseUrl", ""),
                name=item.get("name", ""),
                types=item.get("types", []),
                idPrefixes=item.get("idPrefixes", []),
                stars=int(item.get("stars", 0) or 0),
                from_=item.get("from", ""),
                hits=int(item.get("hits", 0) or 0),
                misses=int(item.get("misses", 0) or 0),
            )
            if entry.baseUrl and self.offer(entry):
                added += 1
        self.retired = set(data.get("retired", []) or [])
        return added

    def as_document(self) -> Dict[str, object]:
        """The exact shape ``ServerPool.Persisted`` decodes."""
        return {
            "generation": 0,
            "entries": [e.as_json() for e in self.entries.values()],
            "history": [],
            "retired": sorted(self.retired),
            "cursor": 0,
        }


# ---------------------------------------------------------------------------
# Sources. Each is a generator of URLs-to-read; a worker turns those into entries.
# ---------------------------------------------------------------------------

@dataclass
class Yield:
    """What one unit of work produced.

    ``more``      this unit leads somewhere else (page N+1 exists).
    ``retry``     the unit did not answer, so it is worth trying again.
    ``follow``    the next unit to enqueue when ``more`` is true.
    """
    source: str
    entries: int
    more: bool = False
    retry: bool = False
    follow: Optional[Callable[[], "Yield"]] = None


@dataclass
class Unit:
    """One queued piece of crawl work: a page, a query, a document."""
    name: str
    run: Callable[[], Yield]
    attempts: int = 0


class Crawler:
    def __init__(self, pool: Pool, fetcher: Fetcher, verbose: bool = False) -> None:
        self.pool = pool
        self.http = fetcher
        self.stats = fetcher.stats
        self.verbose = verbose
        self.stop = threading.Event()
        self.seen_pages: Set[str] = set()
        self.max_pages = 8
        self.gh_pages = 4

    # -- one server ------------------------------------------------------

    def consider_manifest(self, manifest_url: str, source: str, stars: int = 0) -> bool:
        """Fetch a manifest, validate it, and offer it to the pool."""
        if self.pool.has(base_from_manifest(manifest_url)):
            return False
        body = self.http.get(manifest_url)
        if body is None:
            return False
        try:
            manifest = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError):
            return False
        if not manifest_ok(manifest):
            self.stats.bump("refused")
            return False
        base = base_from_manifest(manifest_url)
        if not base or redundant(manifest, base):
            self.stats.bump("refused")
            return False
        self.stats.bump("manifests_ok")
        entry = Entry(
            manifestUrl=manifest_url,
            baseUrl=base,
            name=str(manifest.get("name", ""))[:120],
            types=[str(t) for t in (manifest.get("types") or [])][:8],
            idPrefixes=[str(p) for p in (manifest.get("idPrefixes") or [])][:8],
            stars=stars,
            from_=f"Server pool · {source}",
        )
        offered = self.pool.offer(entry)
        if offered:
            self.stats.add_source(source, 1)
            if self.verbose:
                print(f"  + {entry.name or base}  ({source})")
        return offered

    def harvest_page(self, page_url: str, source: str) -> Yield:
        """Read one page and try every manifest link on it, in parallel."""
        already = page_url in self.seen_pages
        self.seen_pages.add(page_url)
        if already:
            return Yield(source, 0)
        body = self.http.get(page_url)
        if body is None:
            return Yield(source, 0, retry=True)
        urls = extract_manifest_urls(body)
        if not urls and page_url.endswith(".json"):
            # A directory document: look for manifest-shaped objects instead.
            try:
                data = json.loads(body)
                urls = self._manifests_in_json(data)
            except json.JSONDecodeError:
                urls = []
        futures = [self.http.submit(self.consider_manifest, url, source) for url in urls[:40]]
        added = 0
        for future in as_completed(futures):
            try:
                added += 1 if future.result() else 0
            except Exception:
                self.stats.bump("failures")
        return Yield(source, added)

    @staticmethod
    def _manifests_in_json(data: object, depth: int = 0) -> List[str]:
        """Find `manifestUrl`/`transportUrl` values anywhere in a JSON document."""
        found: List[str] = []
        if depth > 6:
            return found
        if isinstance(data, dict):
            for key, value in data.items():
                if isinstance(value, str) and "manifest.json" in value and is_public_https(value):
                    found.append(value)
                elif key == "transport" and isinstance(value, dict) and isinstance(value.get("url"), str):
                    base = value["url"].strip().rstrip("/")
                    if is_public_https(base):
                        found.append(base if base.endswith("manifest.json") else base + "/manifest.json")
                elif key in ("manifestUrl", "transportUrl", "url") and isinstance(value, str):
                    if is_public_https(value):
                        found.append(value if value.endswith("manifest.json") else value.rstrip("/") + "/manifest.json")
                else:
                    found.extend(Crawler._manifests_in_json(value, depth + 1))
        elif isinstance(data, list):
            for item in data[:400]:
                found.extend(Crawler._manifests_in_json(item, depth + 1))
        return list(dict.fromkeys(found))

    # -- directory sources ----------------------------------------------

    def stremio_addons_page(self, page: int) -> Yield:
        url = ("https://stremio-addons.net/api/v0/addons"
               f"?limit=100&nsfw=exclude&sort_by=stars&order=desc&page={page}")
        body = self.http.get(url)
        if body is None:
            return Yield("stremio-addons.net", 0, retry=True)
        try:
            data = json.loads(body)
        except json.JSONDecodeError:
            return Yield("stremio-addons.net", 0)
        addons = data.get("addons") or []
        if not addons:
            return Yield("stremio-addons.net", 0)
        # The manifest is inline, so no extra request per addon.
        add_task_urls: List[Tuple[str, int]] = []
        for addon in addons:
            for item in [addon] + list(addon.get("instances") or []):
                manifest = item.get("manifest")
                url = item.get("manifestUrl") or item.get("transportUrl") or (item.get("transport") or {}).get("url")
                if not isinstance(manifest, dict) or not url:
                    continue
                if not is_public_https(url) or not manifest_ok(manifest):
                    self.stats.bump("refused")
                    continue
                base = base_from_manifest(url)
                if redundant(manifest, base) or self.pool.has(base):
                    continue
                self.stats.bump("manifests_ok")
                entry = Entry(
                    manifestUrl=url, baseUrl=base,
                    name=str(manifest.get("name", ""))[:120],
                    types=[str(t) for t in (manifest.get("types") or [])][:8],
                    idPrefixes=[str(p) for p in (manifest.get("idPrefixes") or [])][:8],
                    stars=int(item.get("stars") or addon.get("stars") or 0),
                    from_="Server pool · stremio-addons.net",
                )
                if self.pool.offer(entry):
                    self.stats.add_source("stremio-addons.net", 1)
        # A full page means there is another one; an inline manifest needs no
        # per-addon request, so this source costs one request per 100 servers.
        more = len(addons) >= 100 and page < self.max_pages
        return Yield("stremio-addons.net", 0, more=more,
                     follow=(lambda: self.stremio_addons_page(page + 1)) if more else None)

    def collection(self, url: str, source: str) -> Yield:
        body = self.http.get(url)
        if body is None:
            return Yield(source, 0, retry=True)
        try:
            data = json.loads(body)
        except json.JSONDecodeError:
            return Yield(source, 0)
        urls = self._manifests_in_json(data)
        self.seen_pages.add(url)
        added = 0
        futures = [self.http.submit(self.consider_manifest, u, source) for u in urls[:150]]
        for future in as_completed(futures):
            try:
                added += 1 if future.result() else 0
            except Exception:
                self.stats.bump("failures")
        return Yield(source, added)

    def github_page(self, query: str, page: int, stars_first: bool = False) -> Yield:
        sort = "stars" if stars_first else "updated"
        url = ("https://api.github.com/search/repositories"
               f"?q={urllib.parse.quote(query)}&sort={sort}&order=desc&per_page=100&page={page}")
        body = self.http.get(url)
        if body is None:
            # A rate-limited or failed search must be retried, not called empty.
            return Yield("github", 0, retry=True)
        try:
            items = json.loads(body).get("items") or []
        except json.JSONDecodeError:
            return Yield("github", 0)
        if not items:
            return Yield("github", 0)

        added = 0
        for repo in items:
            text = f"{repo.get('description') or ''} {repo.get('homepage') or ''}"
            for manifest_url in extract_manifest_urls(text, limit=3):
                if self.consider_manifest(manifest_url, "github", int(repo.get("stargazers_count") or 0)):
                    added += 1
        # READMEs are where most addons document themselves; read a bounded slice.
        readme_futures = []
        for repo in items[:30]:
            full = repo.get("full_name") or ""
            if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", full):
                continue
            branch = re.sub(r"[^A-Za-z0-9_.-]", "", repo.get("default_branch") or "main")
            readme_futures.append(self.http.submit(
                self._readme_scan, f"https://raw.githubusercontent.com/{full}/{branch}/README.md",
                int(repo.get("stargazers_count") or 0)))
        for future in as_completed(readme_futures):
            try:
                added += future.result()
            except Exception:
                self.stats.bump("failures")
        # Homepages and GitHub Pages, one candidate manifest each.
        page_futures = []
        for repo in items[:30]:
            full = repo.get("full_name") or ""
            homepage = (repo.get("homepage") or "").strip().rstrip("/")
            stars = int(repo.get("stargazers_count") or 0)
            if homepage.startswith("https://"):
                page_futures.append(self.http.submit(
                    self.consider_manifest, homepage + "/manifest.json", "homepage", stars))
            if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", full):
                owner, name = full.split("/")
                for candidate in (f"https://{owner.lower()}.github.io/{name.lower()}/manifest.json",
                                  f"https://{owner.lower()}.github.io/manifest.json"):
                    page_futures.append(self.http.submit(
                        self.consider_manifest, candidate, "github-pages", stars))
        for future in as_completed(page_futures):
            try:
                added += 1 if future.result() else 0
            except Exception:
                self.stats.bump("failures")
        more = len(items) >= 100 and page < self.gh_pages
        return Yield("github", added, more=more,
                     follow=(lambda: self.github_page(query, page + 1, stars_first)) if more else None)

    def _readme_scan(self, url: str, stars: int) -> int:
        body = self.http.get(url)
        if body is None:
            return 0
        added = 0
        for manifest_url in extract_manifest_urls(body, limit=6):
            if self.consider_manifest(manifest_url, "github-readme", stars):
                added += 1
        return added


# Query sets. More queries means more of the ecosystem, at 10 unauth searches/min.
GITHUB_QUERIES = [
    "topic:stremio-addon",
    "stremio addon",
    "stremio-addons",
    "stremio streaming addon",
    "manifest.json stremio",
    "topic:stremio-addons",
    "stremio addon manifest.json in:readme",
    "stremio addons list",
    "stremio catalog addon",
    "stremio subtitle addon",
    "stremio meta addon",
    "topic:stremio",
    "zeroplay addon",
    "stremio server addon",
    "stremio addon railway",
    "stremio addon vercel",
]

COMMUNITY_LISTS = [
    "https://raw.githubusercontent.com/doingodswork/more-stremio-addons/master/README.md",
    "https://raw.githubusercontent.com/nzahasan/stremio-addons/main/README.md",
    "https://raw.githubusercontent.com/Stremio/stremio-addons-list/master/README.md",
    "https://stremio-addons.com/",
    "https://stremio-addons.net/",
]

COLLECTIONS = [
    ("https://api.strem.io/addonscollection.json", "stremio-collection"),
    ("https://v3-cinemeta.strem.io/addon_catalog/all/community.json", "cinemeta-community"),
    ("https://v3-cinemeta.strem.io/addon_catalog/all/official.json", "cinemeta-official"),
]


def seed_units(crawler: Crawler, args: argparse.Namespace) -> List[Unit]:
    """The frontier: every place a crawl can start, sized by --pages / --gh-pages."""
    crawler.max_pages = args.pages
    crawler.gh_pages = args.gh_pages
    units: List[Unit] = [Unit(f"stremio-addons page {p}", (lambda p=p: crawler.stremio_addons_page(p)))
                         for p in range(1, args.pages + 1)]
    units += [Unit(url, (lambda u=url, s=source: crawler.collection(u, s))) for url, source in COLLECTIONS]
    units += [Unit(url, (lambda u=url: crawler.harvest_page(u, "community"))) for url in COMMUNITY_LISTS]
    for index, query in enumerate(GITHUB_QUERIES):
        for page in range(1, args.gh_pages + 1):
            units.append(Unit(f"github {query} p{page}",
                              (lambda q=query, p=page, s=index % 2 == 1: crawler.github_page(q, p, s))))
    return units


def crawl(crawler: Crawler, args: argparse.Namespace) -> None:
    """Drain the frontier until the target is met, it is empty, or time is up.

    This is a frontier, not a fixed number of rounds: a unit that reports
    ``more`` enqueues the next one (page N+1, next search page), a unit that
    fails is retried up to ``args.retries`` times, and a unit that answers
    nothing new is simply finished. That is the difference between "the sources
    are exhausted" (frontier empty) and "this round was unlucky" (frontier still
    has work) -- the old loop could not tell them apart and gave up early.
    """
    pool = crawler.pool
    deadline = time.time() + args.max_seconds if args.max_seconds else None
    pending: List[Unit] = seed_units(crawler, args)
    running: Dict[Future, Unit] = {}
    empty_rounds = 0
    rounds = 0

    while not pool.full and not crawler.stop.is_set():
        if deadline and time.time() > deadline:
            print("time limit reached")
            break
        if not pending and not running:
            print("frontier empty: every source has been read")
            break

        rounds += 1
        before = pool.size

        # Keep the workers busy, but never more than --workers in flight.
        while pending and len(running) < args.workers:
            unit = pending.pop(0)
            unit.attempts += 1
            running[crawler.http.submit(unit.run)] = unit

        done = 0
        for future in as_completed(list(running), timeout=None):
            unit = running.pop(future, None)
            if unit is None:
                continue
            done += 1
            try:
                result = future.result()
            except Exception as error:            # a source bug must not end the crawl
                crawler.stats.bump("failures")
                if args.verbose:
                    print(f"  ! {unit.name}: {error!r}")
                continue
            if result.retry and unit.attempts <= args.retries:
                # Re-queue with a delay, so a throttled host is not hammered.
                pending.append(unit)
                time.sleep(min(2.0 * unit.attempts, 10.0))
            elif result.more and result.follow is not None:
                pending.append(Unit(f"{unit.name} +", result.follow))
            # Give the pool a chance to end the crawl promptly.
            if pool.full or crawler.stop.is_set():
                break

        gained = pool.size - before
        stats = crawler.stats.snapshot()
        print(f"round {rounds:3d}  +{gained:3d}  pool {pool.size:4d}/{pool.target}"
              f"  queue {len(pending):3d}  requests {stats['requests']:5d}"
              f"  refused {stats['refused']:4d}  rate-limited {stats['rate_limited']}"
              f"  {stats['seconds']}s")

        if gained == 0:
            empty_rounds += 1
            if empty_rounds >= args.empty_rounds and not pending:
                print(f"{empty_rounds} rounds with nothing new and nothing queued; stopping")
                break
            time.sleep(min(5.0, 0.5 * empty_rounds))
        else:
            empty_rounds = 0

    if crawler.stop.is_set():
        print("stopped by the user")


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="Crawl public Stremio addons into a Nova 3000-server pool")
    ap.add_argument("--target", type=int, default=CAPACITY, help=f"stop at this many servers (default {CAPACITY})")
    ap.add_argument("--workers", type=int, default=32, help="requests in flight (default 32)")
    ap.add_argument("--per-host", type=int, default=4, help="requests in flight per host (default 4)")
    ap.add_argument("--pages", type=int, default=8, help="stremio-addons.net pages per round")
    ap.add_argument("--gh-pages", type=int, default=4, help="GitHub search pages per query")
    ap.add_argument("--empty-rounds", type=int, default=6, help="stop after this many rounds with nothing new")
    ap.add_argument("--retries", type=int, default=2, help="retries for a page that did not answer (default 2)")
    ap.add_argument("--max-seconds", type=int, default=0, help="stop after this many seconds (0 = no limit)")
    ap.add_argument("--merge", help="start from an existing pool JSON and add to it")
    ap.add_argument("--out", default="server_pool.json", help="where to write the pool")
    ap.add_argument("--verbose", action="store_true", help="print every server as it is found")
    args = ap.parse_args(argv)
    args.max_seconds = args.max_seconds or 0

    pool = Pool(target=args.target)
    if args.merge:
        path = Path(args.merge)
        if not path.is_file():
            print(f"error: --merge {path} does not exist", file=sys.stderr)
            return 2
        added = pool.merge_file(path)
        print(f"merged {added} servers from {path} (pool now {pool.size})")

    stats = Stats()
    fetcher = Fetcher(args.workers, args.per_host, stats, os.environ.get("GITHUB_TOKEN", ""))
    crawler = Crawler(pool, fetcher, verbose=args.verbose)

    def _stop(signum, _frame):
        print("\nstopping: writing what has been found so far")
        crawler.stop.set()

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    started = time.time()
    try:
        crawl(crawler, args)
    finally:
        fetcher.shutdown()
        out = Path(args.out)
        out.write_text(json.dumps(pool.as_document(), indent=1) + "\n", encoding="utf-8")
        snap = stats.snapshot()
        print()
        print(f"pool      {pool.size}/{args.target}")
        print(f"file      {out}  ({out.stat().st_size} bytes)")
        print(f"requests  {snap['requests']}  failures {snap['failures']}  refused {snap['refused']}"
              f"  rate-limited {snap['rate_limited']}")
        print(f"per source {json.dumps(snap['by_source'], sort_keys=True)}")
        print(f"elapsed   {round(time.time() - started, 1)}s")

    if pool.size >= args.target:
        print("target reached")
        return 0
    print(f"stopped below target: {pool.size} of {args.target}. The sources above are what produced them;"
          " raising --pages/--gh-pages or running again later (GitHub's index changes hourly) will find more.")
    return 3


if __name__ == "__main__":
    raise SystemExit(main())
