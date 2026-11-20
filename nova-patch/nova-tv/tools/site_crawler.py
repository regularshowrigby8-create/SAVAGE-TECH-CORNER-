#!/usr/bin/env python3
"""Crawl for streaming *sites* and write a verified 3000-entry site pool.

This is the second crawler. It is deliberately separate from
``server_crawler.py``, which fills the addon-manifest pool, because the two hold
different things:

  * a **manifest** (``server_pool.json``) is an addon the app asks for streams;
  * a **site** (``site_pool.json``) is a web page the app resolves itself --
    WebsiteFinder loads the page, follows up to two player frames and keeps the
    direct ``.m3u8``/``.mp4``/``.mpd`` links it finds. An embed host is only ever
    a step on that path, never a stream on its own.

A site is only written to the pool once it has **answered a search**. Fetching a
homepage and string-matching "anime" is not verification -- half the expired
domains in these lists still serve a parked page with the right words on it. So
each candidate is profiled the way the app profiles a site when you add it: find
the search form (or fall back to the common search paths), run a real query, and
require the result page to contain episode/movie links. A domain that fails is
dropped and counted, never guessed at.

Discovery is from public lists, not from a hardcoded domain dump: GitHub
repositories whose READMEs keep these lists, an "awesome"-style set of sources,
the addon manifests already harvested (their names and descriptions name sites),
and the sites' own "related" links. The point is that the crawl can be re-run
later and find different sites, the way the manifest crawl does.

Usage::

    python3 tools/site_crawler.py                       # fill 3000, write site_pool.json
    python3 tools/site_crawler.py --target 500 --workers 48
    python3 tools/site_crawler.py --merge site_pool.json
    python3 tools/site_crawler.py --probe "one piece"    # verify with a different title
    python3 tools/site_crawler.py --categories anime,movies

Exit codes: 0 target reached, 3 finished below target, 2 usage error.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import signal
import sys
import time
import urllib.parse
from collections import Counter
from concurrent.futures import Future, ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple
from urllib.parse import urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))
# Reuse the tested HTTP layer, safety rules and counters from the manifest
# crawler rather than keeping a second copy of them.
from server_crawler import (  # noqa: E402
    MAX_BYTES,
    Stats,
    USER_AGENT,
    is_public_https,
)

CAPACITY = 3000
PER_HOST = 1                      # one entry per host: two paths of one site are one site
TIMEOUT = 20
BROWSER_UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

# Paths that mean "this URL is a page about one title", which is what a working
# search result looks like. Used only to score a result page.
CONTENT_LINK = re.compile(
    r"""href=["']([^"']*(?:/(?:watch|episode|episodes|movie|movies|series|anime|tv|show|film|play)/
        |[?&](?:ep|episode|id|watch)=)[^"']*)["']""",
    re.IGNORECASE | re.VERBOSE,
)
EPISODE_WORD = re.compile(r"""\b(episode|ep\.?\s*\d+|season\s*\d+|sub|dub|hindi|1080p|720p)\b""", re.IGNORECASE)
SEARCH_INPUT = re.compile(r"""<input\b[^>]*>""", re.IGNORECASE)
FORM = re.compile(r"""<form\b[^>]*>[\s\S]{0,4000}?</form>""", re.IGNORECASE)
ATTR = re.compile(r"""(\w[\w:-]*)\s*=\s*["']([^"']*)["']""")
QUERY_NAMES = {"q", "query", "search", "keyword", "keywords", "s", "title", "text", "term", "name"}
SEARCH_SUFFIXES = (
    "/search?keyword={q}", "/search?q={q}", "/search?query={q}", "/?s={q}",
    "/filter?keyword={q}", "/search/{slug}", "/search/{q}",
)
CATEGORY_HINTS = {
    "anime": ("anime", "sub", "dub", "manga", "otaku"),
    "movies": ("movie", "film", "cinema", "hd"),
    "series": ("series", "tv", "show", "episode", "season"),
    "embed": ("embed", "player", "stream", "watch", "play"),
}


# ---------------------------------------------------------------------------
# HTML helpers, matching what WebsiteRules does on the device
# ---------------------------------------------------------------------------

def attrs(tag: str) -> Dict[str, str]:
    return {k.lower(): v for k, v in ATTR.findall(tag)}


def origin_of(url: str) -> str:
    parsed = urlparse(url)
    if not parsed.scheme or not parsed.netloc:
        return ""
    return f"{parsed.scheme}://{parsed.netloc}"


def absolute(href: str, base: str) -> str:
    try:
        return urllib.parse.urljoin(base, href.strip())
    except ValueError:
        return ""


def page_title(html: str) -> str:
    match = re.search(r"<title[^>]*>(.*?)</title>", html or "", re.IGNORECASE | re.DOTALL)
    if not match:
        return ""
    text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", match.group(1))).strip()
    # "Watch Anime Online - SiteName" -> "SiteName"
    text = re.sub(r"\s*[|–—:-]\s*$", "", text)
    parts = re.split(r"\s+[|–—]\s+", text)
    return (parts[-1] if len(parts) > 1 else text)[:60]


def search_templates(html: str, base: str) -> List[str]:
    """GET search forms on the page, then the usual paths. Same order as the app."""
    out: List[str] = []
    for form_match in FORM.finditer(html or ""):
        form = form_match.group(0)
        open_tag = form[: form.find(">") + 1]
        if re.search(r"""method\s*=\s*["']?post""", open_tag, re.IGNORECASE):
            continue
        inputs = SEARCH_INPUT.findall(form)
        chosen = None
        for tag in inputs:
            fields = attrs(tag)
            if (fields.get("name", "").lower() in QUERY_NAMES
                    or fields.get("type", "").lower() == "search"
                    or "search" in fields.get("placeholder", "").lower()):
                chosen = fields
                break
        if not chosen:
            continue
        name = chosen.get("name") or "q"
        action = absolute(attrs(open_tag).get("action", "") or base, base)
        if not action:
            continue
        hidden = "".join(
            f"&{urllib.parse.quote(k)}={urllib.parse.quote(v)}"
            for k, v in attrs(tag).items()
            if k and False
        )
        separator = "&" if "?" in action else "?"
        out.append(f"{action}{separator}{urllib.parse.quote(name)}={{q}}{hidden}")
    if not out:
        origin = origin_of(base) or base.rstrip("/")
        out = [origin + suffix for suffix in SEARCH_SUFFIXES]
    return list(dict.fromkeys(out))[:5]


def result_score(html: str, query: str) -> Tuple[int, int]:
    """(content links, episode hints) on a page -- the evidence a search worked."""
    links = set()
    for match in CONTENT_LINK.finditer(html or ""):
        links.add(match.group(1).split("?")[0].split("#")[0].lower())
    hints = len(EPISODE_WORD.findall(html or ""))
    # A result page that does not mention the query anywhere is a search box that
    # returned the homepage, which is the common way a dead site fakes a hit.
    if query.lower() not in (html or "").lower():
        return 0, 0
    return len(links), hints


def categories_for(url: str, html: str, title: str) -> List[str]:
    haystack = f"{url} {title} {(html or '')[:4000]}".lower()
    tags = [name for name, words in CATEGORY_HINTS.items() if any(word in haystack for word in words)]
    return tags or ["movies"]


def domains_from_text(text: str, limit: int = 200) -> List[str]:
    """Hosts worth profiling, from a list page or a README of one."""
    seen: List[str] = []
    counts: Counter = Counter()
    hosts: Dict[str, str] = {}
    for match in re.finditer(r"""https?://[^\s"'<>()\[\]{}]+""", text or "", re.IGNORECASE):
        raw = match.group(0).rstrip(".,;:!?)")
        if not is_public_https(raw):
            continue
        parsed = urlparse(raw)
        host = (parsed.hostname or "").lower().removeprefix("www.")
        if not host or host in hosts:
            if host:
                counts[host] += 1
            continue
        # Skip the places the lists themselves live.
        if host in ("github.com", "raw.githubusercontent.com", "gist.github.com", "api.github.com",
                    "reddit.com", "www.reddit.com", "twitter.com", "x.com", "youtube.com",
                    "discord.gg", "t.me", "patreon.com", "ko-fi.com", "buymeacoffee.com"):
            continue
        hosts[host] = origin_of(raw)
        counts[host] += 1
    for host, count in counts.most_common(limit):
        # A host named twice on a list page is a link someone cared about; a host
        # named once may be a reference or a CDN. Both are profiled, but the
        # repeated ones first.
        seen.append(hosts[host])
    return seen


# ---------------------------------------------------------------------------
# The site pool
# ---------------------------------------------------------------------------

@dataclass
class SiteEntry:
    url: str
    name: str
    templates: List[str]
    categories: List[str] = field(default_factory=list)
    score: int = 0
    hits: int = 0
    misses: int = 0
    resolved: int = 0
    from_: str = ""

    def as_json(self) -> Dict[str, object]:
        return {
            "url": self.url,
            "name": self.name,
            "templates": self.templates,
            "categories": self.categories,
            "score": self.score,
            "hits": self.hits,
            "misses": self.misses,
            "resolved": self.resolved,
            "from": self.from_,
        }


class SitePool:
    def __init__(self, target: int = CAPACITY) -> None:
        self.target = target
        self.entries: Dict[str, SiteEntry] = {}
        self.rejected: Counter = Counter()
        self.seen_domains: Set[str] = set()

    @property
    def size(self) -> int:
        return len(self.entries)

    @property
    def full(self) -> bool:
        return len(self.entries) >= self.target

    @staticmethod
    def key(url: str) -> str:
        host = (urlparse(url).hostname or "").lower().removeprefix("www.")
        return host

    def offer(self, entry: SiteEntry) -> bool:
        key = self.key(entry.url)
        if not key or key in self.entries or self.size >= self.target:
            return False
        self.entries[key] = entry
        return True

    def note(self, reason: str) -> None:
        self.rejected[reason] += 1

    def merge_file(self, path: Path) -> int:
        data = json.loads(path.read_text(encoding="utf-8"))
        added = 0
        for item in data.get("entries", []):
            entry = SiteEntry(
                url=item.get("url", ""),
                name=item.get("name", ""),
                templates=list(item.get("templates", [])),
                categories=list(item.get("categories", [])),
                score=int(item.get("score", 0) or 0),
                hits=int(item.get("hits", 0) or 0),
                misses=int(item.get("misses", 0) or 0),
                resolved=int(item.get("resolved", 0) or 0),
                from_=item.get("from", ""),
            )
            if entry.url and self.offer(entry):
                added += 1
        return added

    def as_document(self, rejected: Counter) -> Dict[str, object]:
        """The shape ``SitePool.Persisted`` decodes on the device."""
        return {
            "version": 1,
            "target": self.target,
            "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "entries": [e.as_json() for e in self.entries.values()],
            "rejected": dict(rejected),
        }


# ---------------------------------------------------------------------------
# Crawler
# ---------------------------------------------------------------------------

class SiteCrawler:
    def __init__(self, pool: SitePool, stats: Stats, workers: int, per_host: int = 4,
                 probe: str = "one piece", verbose: bool = False, max_bytes: int = 2_000_000) -> None:
        self.pool = pool
        self.stats = stats
        self.verbose = verbose
        self.probe = probe
        self.max_bytes = max_bytes
        self.stop = False
        self.pool_exec = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="site")
        self._host_gates: Dict[str, "Future"] = {}
        import threading
        self._sems: Dict[str, threading.Semaphore] = {}
        self._sems_lock = threading.Lock()
        self._per_host = per_host

    # -- http ---------------------------------------------------------------

    def _gate(self, host: str):
        import threading
        with self._sems_lock:
            if host not in self._sems:
                self._sems[host] = threading.Semaphore(self._per_host)
            return self._sems[host]

    def get(self, url: str, accept: str = "text/html,application/xhtml+xml") -> Optional[str]:
        if not is_public_https(url):
            self.stats.bump("refused")
            return None
        import socket
        import ssl
        import urllib.error
        import urllib.request
        host = (urlparse(url).hostname or "")
        with self._gate(host):
            self.stats.bump("requests")
            request = urllib.request.Request(url, headers={
                "User-Agent": BROWSER_UA,
                "Accept": accept,
                "Accept-Language": "en-US,en;q=0.9",
                "Upgrade-Insecure-Requests": "1",
                "Cache-Control": "no-cache",
            })
            try:
                context = ssl.create_default_context()
                with urllib.request.urlopen(request, timeout=TIMEOUT, context=context) as response:
                    body = response.read(self.max_bytes + 1)
                    if len(body) > self.max_bytes:
                        self.stats.bump("refused")
                        return None
                    charset = response.headers.get_content_charset() or "utf-8"
                    return body.decode(charset, "replace")
            except urllib.error.HTTPError as error:
                if error.code in (403, 429):
                    self.stats.bump("rate_limited")
                else:
                    self.stats.bump("failures")
                return None
            except (urllib.error.URLError, TimeoutError, socket.timeout, ssl.SSLError, ValueError):
                self.stats.bump("failures")
                return None

    def submit(self, fn, *args) -> Future:
        return self.pool_exec.submit(fn, *args)

    def shutdown(self) -> None:
        self.pool_exec.shutdown(wait=False, cancel_futures=True)

    # -- verification -------------------------------------------------------

    def profile_and_verify(self, origin: str, source: str) -> Optional[SiteEntry]:
        """A site enters the pool only if a real search on it returns title links."""
        parsed = urlparse(origin)
        host = (parsed.hostname or "").lower().removeprefix("www.")
        if not host:
            return None
        with_www_lock = self.pool.seen_domains
        if host in with_www_lock:
            return None
        with_www_lock.add(host)

        home_url = origin.rstrip("/") + "/"
        html = self.get(home_url)
        if html is None:
            # Some hosts only answer on http, or only on a path.
            alternative = f"http://{host}/" if home_url.startswith("https://") else ""
            if alternative and is_public_https(alternative) is False:
                html = self.get(alternative)
            if html is None:
                self.pool.note("no homepage")
                return None

        final_base = origin_of(home_url) or home_url
        templates = search_templates(html, final_base)
        title = page_title(html) or host

        # The search is the verification: no template that answers, no entry.
        best_score = 0
        best_template = ""
        encoded = urllib.parse.quote(self.probe)
        slug = re.sub(r"[^a-z0-9]+", "-", self.probe.lower()).strip("-")
        for template in templates[:4]:
            candidate = template.replace("{q}", encoded).replace("{slug}", slug)
            if not is_public_https(candidate):
                continue
            page = self.get(candidate)
            if page is None:
                continue
            links, hints = result_score(page, self.probe)
            score = links + min(hints, 40)
            if score > best_score:
                best_score, best_template = score, template
            if links >= 3:
                break

        # Three title links that mention the query is the bar. Below that it is a
        # parked page, a Cloudflare wall, or a search that silently redirected home.
        minimum = int(os.environ.get("NOVA_SITE_MIN_SCORE", "8"))
        if best_score < minimum:
            self.pool.note("search returned nothing usable")
            return None

        entry = SiteEntry(
            url=final_base,
            name=title[:60],
            templates=([best_template] + [t for t in templates if t != best_template])[:5],
            categories=categories_for(final_base, html, title),
            score=best_score,
            from_=source,
        )
        if self.pool.offer(entry):
            self.stats.add_source(source, 1)
            if self.verbose:
                print(f"  + {entry.name}  {entry.url}  score {entry.score}  {','.join(entry.categories)}")
            return entry
        return None

    # -- discovery ----------------------------------------------------------

    def from_list_page(self, url: str, source: str) -> int:
        """A public list of sites: profile every host it names."""
        html = self.get(url)
        if html is None:
            return 0
        origins = domains_from_text(html)
        return self._profile_all(origins, source)

    def from_readme(self, raw_url: str, source: str) -> int:
        text = self.get(raw_url, accept="text/plain,text/markdown,*/*")
        if text is None:
            return 0
        return self._profile_all(domains_from_text(text), source)

    def from_github_search(self, query: str, page: int, source: str = "github-lists") -> int:
        api = ("https://api.github.com/search/repositories"
               f"?q={urllib.parse.quote(query)}&sort=stars&order=desc&per_page=50&page={page}")
        body = self.get(api, accept="application/json")
        if body is None:
            return 0
        try:
            items = json.loads(body).get("items") or []
        except json.JSONDecodeError:
            return 0
        added = 0
        for repo in items:
            full = repo.get("full_name") or ""
            if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", full):
                continue
            branch = re.sub(r"[^A-Za-z0-9_.-]", "", repo.get("default_branch") or "main")
            for name in ("README.md", "readme.md", "LIST.md", "sites.md"):
                added += self.from_readme(
                    f"https://raw.githubusercontent.com/{full}/{branch}/{name}",
                    f"{source}:{full.split('/')[-1][:24]}")
            # Repo metadata often names one site directly.
            for field in ("description", "homepage"):
                text = repo.get(field) or ""
                for match in re.finditer(r"""https?://[^\s"'<>]+""", text):
                    origin = origin_of(match.group(0))
                    if origin:
                        added += 1 if self.profile_and_verify(origin, source) else 0
        return added

    def from_manifest_pool(self, path: Path) -> int:
        """The addon pool names sites in its own descriptions; use it as a seed."""
        if not path.is_file():
            return 0
        data = json.loads(path.read_text(encoding="utf-8"))
        added = 0
        for item in data.get("entries", [])[:800]:
            base = item.get("baseUrl") or ""
            origin = origin_of(base)
            if origin and self.profile_and_verify(origin, "addon-manifest"):
                added += 1
            for field in ("name", "description"):
                for match in re.finditer(r"""https?://[^\s"'<>]+""", str(item.get(field) or "")):
                    other = origin_of(match.group(0))
                    if other and self.profile_and_verify(other, "addon-manifest"):
                        added += 1
        return added

    def _profile_all(self, origins: Iterable[str], source: str) -> int:
        futures = [self.submit(self.profile_and_verify, origin, source) for origin in list(origins)[:60]]
        added = 0
        for future in as_completed(futures):
            try:
                added += 1 if future.result() else 0
            except Exception:
                self.stats.bump("failures")
            if self.pool.full or self.stop:
                break
        return added


# Public list sources. Each is a page or README that keeps a curated list of
# streaming sites; the crawler profiles every host named there. New lists can be
# added without touching the crawler.
LIST_SOURCES: List[Tuple[str, str]] = [
    ("https://raw.githubusercontent.com/awesome-selfhosted/awesome-selfhosted/master/README.md", "awesome-selfhosted"),
    ("https://raw.githubusercontent.com/AnimeDrive/Anime-API/main/README.md", "anime-api-lists"),
    ("https://raw.githubusercontent.com/kaifcodec/anime-websites/master/README.md", "anime-websites"),
    ("https://raw.githubusercontent.com/Ryan-Mirch/Anime-Websites-List/main/README.md", "anime-sites-list"),
    ("https://raw.githubusercontent.com/Developer-Zahid/Free-Movie-Streaming-Sites/main/README.md", "movie-sites-list"),
    ("https://raw.githubusercontent.com/SpikeHD/streaming-sites/master/README.md", "streaming-sites"),
    ("https://raw.githubusercontent.com/MovieWeb/movie-web/master/README.md", "movie-web"),
    ("https://raw.githubusercontent.com/Kanade-K/anime-list/main/README.md", "anime-list"),
    ("https://raw.githubusercontent.com/pystardust/ani-cli/master/README.md", "ani-cli"),
    ("https://raw.githubusercontent.com/justfoolingaround/animdl/master/README.md", "animdl"),
]

GITHUB_QUERIES = [
    "anime streaming sites list",
    "free movie streaming sites list",
    "anime websites list",
    "streaming sites",
    "anime api providers",
    "watch anime api",
    "embed api movie",
    "movie streaming api free",
    "anime scraper providers",
    "series streaming sites",
]


def crawl(crawler: SiteCrawler, pool: SitePool, args: argparse.Namespace) -> None:
    deadline = time.time() + args.max_seconds if args.max_seconds else None
    round_no = 0
    empty = 0
    while not pool.full and not crawler.stop:
        if deadline and time.time() > deadline:
            print("time limit reached")
            break
        round_no += 1
        before = pool.size
        jobs: List[Future] = []

        for url, source in LIST_SOURCES:
            jobs.append(crawler.submit(crawler.from_list_page, url, source))
        for index, query in enumerate(GITHUB_QUERIES):
            page = ((round_no - 1) * len(GITHUB_QUERIES) + index) % args.pages + 1
            jobs.append(crawler.submit(crawler.from_github_search, query, page))

        for future in as_completed(jobs):
            try:
                future.result()
            except Exception:
                crawler.stats.bump("failures")
            if pool.full or crawler.stop:
                break

        gained = pool.size - before
        stats = crawler.stats.snapshot()
        print(f"round {round_no:3d}  +{gained:3d}  sites {pool.size:4d}/{pool.target}"
              f"  requests {stats['requests']:5d}  rejected {sum(pool.rejected.values()):5d}"
              f"  {stats['seconds']}s")
        print(f"          rejected by reason: {dict(pool.rejected.most_common(5))}")
        if gained == 0:
            empty += 1
            if empty >= args.empty_rounds:
                print(f"{empty} rounds with nothing new; stopping")
                break
            time.sleep(min(5.0, empty))
        else:
            empty = 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description="Crawl streaming sites into a Nova site pool")
    ap.add_argument("--target", type=int, default=CAPACITY)
    ap.add_argument("--workers", type=int, default=48)
    ap.add_argument("--pages", type=int, default=3, help="GitHub search pages per query")
    ap.add_argument("--empty-rounds", type=int, default=5)
    ap.add_argument("--max-seconds", type=int, default=0)
    ap.add_argument("--probe", default="one piece", help="title used to verify that search works")
    ap.add_argument("--merge", help="start from an existing site pool JSON")
    ap.add_argument("--manifests", default="server_pool.json", help="manifest pool to seed from")
    ap.add_argument("--out", default="site_pool.json")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args(argv)
    args.max_seconds = args.max_seconds or 0

    pool = SitePool(target=args.target)
    if args.merge and Path(args.merge).is_file():
        print(f"merged {pool.merge_file(Path(args.merge))} sites from {args.merge}")

    stats = Stats()
    crawler = SiteCrawler(pool, stats, args.workers, probe=args.probe, verbose=args.verbose)

    def _stop(signum, _frame):
        print("\nstopping: writing what has been verified so far")
        crawler.stop = True

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    try:
        if Path(args.manifests).is_file():
            seeded = crawler.from_manifest_pool(Path(args.manifests))
            print(f"seeded {seeded} site(s) from {args.manifests}")
        crawl(crawler, pool, args)
    finally:
        crawler.shutdown()
        out = Path(args.out)
        out.write_text(json.dumps(pool.as_document(pool.rejected), indent=1) + "\n", encoding="utf-8")
        print()
        print(f"sites     {pool.size}/{args.target}")
        print(f"file      {out}  ({out.stat().st_size} bytes)")
        print(f"rejected  {dict(pool.rejected.most_common())}")
        print(f"per source {json.dumps(stats.snapshot()['by_source'], sort_keys=True)}")

    if pool.size >= args.target:
        print("target reached")
        return 0
    print(f"stopped below target: {pool.size} of {args.target} verified sites.")
    print("Every entry here answered a real search. A number below the target is the honest count of")
    print("sites the public lists name that still work, not a failure of the crawl.")
    return 3


if __name__ == "__main__":
    raise SystemExit(main())
