#!/usr/bin/env python3
"""Real, bounded crawl probe for the Nova v3 engine registry.

This is deliberately separate from the Kotlin unit-test fixture. It generates
the same round-0 public-index URLs from DiscoveryEngines.kt, fetches them,
applies the same parser/sorter rules, then optionally performs the same
user-like catalogue/server checks on a bounded number of candidate origins.

It never bypasses authentication, CAPTCHAs, paywalls, DRM or private network
rules. A verified site requires observed catalogue links, a title query, an
observed media/stream URL and a successful byte probe. A partial discovery run
is reported as partial; it is not renamed to success.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
ENGINES_FILE = ROOT / "nova-tv/android/app/src/main/java/dev/nova/tv/finder/pipeline/DiscoveryEngines.kt"
LOCAL_ALLOWED_HOSTS = {
    "api.github.com",
    "github.com",
    "codeload.github.com",
    "registry.npmjs.org",
    "pypi.org",
    "files.pythonhosted.org",
}
QUERY_MODIFIERS = ["", "catalogue", "server", "working", "public", "episode", "manifest", "index"]
USER_AGENT = "nova-finder-crawl-probe/1.0 (+public-index-smoke-test)"
GITHUB_MIN_INTERVAL_SECONDS = 3.0
_GITHUB_LOCK = threading.Lock()
_GITHUB_LAST_REQUEST = 0.0

URL_RE = re.compile(r"""https?://[^\s"'<>)]+""", re.I)
HREF_RE = re.compile(r"""href\s*=\s*["']([^"'>]+)["']""", re.I)
HOST_RE = re.compile(r"\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,24}\b", re.I)
MANIFEST_RE = re.compile(r"""https?://[^\s"'<>)]+manifest\.json""", re.I)
ATTR_URL_RE = re.compile(r"""(?:href|src|action|data-url|data-src|url)\s*[:=]\s*["']([^"']{1,500})["']""", re.I)
CATALOGUE_PATH_RE = re.compile(
    r"/(?:movie|movies|series|anime|watch|title|episode|catalog|catalogue|meta|stream|manifest)(?:[/.?#]|$)",
    re.I,
)
API_PATH_RE = re.compile(
    r"/(?:ajax|api|sources|servers|embed|player|stream|watch)(?:[/.?#]|$)", re.I
)
JUNK_PATH_RE = re.compile(
    r"/(?:wp-admin|wp-json|wp-includes|wp-login|xmlrpc\.php|admin-ajax\.php|"
    r"ads?|adserver|adframe|analytics|track(?:ing|er)?|pixel|beacon|prebid|"
    r"gpt|pagead|telemetry|cdn-cgi)(?:[/?#]|$)",
    re.I,
)
ASSET_PATH_RE = re.compile(r"\.(?:js|css|woff2?|ttf|eot|png|jpe?g|gif|svg|ico|map)(?:$|\?)", re.I)
TORRENT_RE = re.compile(r"magnet:|xt=urn:btih|torrentio|jackettio|\.torrent", re.I)
PRIVATE_HOST_RE = re.compile(
    r"^(localhost|127\.0\.0\.1|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|169\.254\.\d+\.\d+|::1|\[::1\])$",
    re.I,
)
IPV4_RE = re.compile(r"^\d{1,3}(?:\.\d{1,3}){3}$")
AD_HOSTS = [
    "doubleclick.net", "googlesyndication.com", "googleadservices.com",
    "google-analytics.com", "googletagmanager.com", "googletagservices.com",
    "adtrafficquality.google", "syndicatedsearch.goog", "adservice.google",
    "facebook.net", "fbcdn.net", "amazon-adsystem.com", "criteo.com",
    "taboola.com", "outbrain.com", "scorecardresearch.com", "quantserve.com",
    "moatads.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com",
    "openx.net", "casalemedia.com", "3lift.com", "sharethrough.com",
]
IRRELEVANT_HOSTS = [
    "google.com", "gstatic.com", "googleapis.com", "bing.com",
    "duckduckgo.com", "youtube.com", "youtu.be", "vimeo.com",
    "wikipedia.org", "wikimedia.org", "fandom.com", "reddit.com",
    "github.com", "githubusercontent.com", "npmjs.com", "npms.io",
    "crt.sh", "urlscan.io", "commoncrawl.org", "web.archive.org",
    "merriam-webster.com", "webflow.com", "shopify.com", "odkmedia.net",
    "akamai.com", "fastly.net", "jsdelivr.net", "unpkg.com", "cdnjs.com",
    "gravatar.com", "bootstrapcdn.com", "jquery.com", "w3.org", "schema.org",
]
MEDIA_WORDS = [
    "movie", "series", "anime", "watch", "stream", "episode", "catalog",
    "catalogue", "kitsu", "drama", "film", "tv", "show", "stremio",
]
STRONGER_WORDS = ["movie", "series", "anime", "watch", "stream", "stremio"]
REDIRECT_KEYS = {"uddg", "u", "url", "r", "target", "redirect"}
PREFERRED_HOSTS = {
    "v3-cinemeta.strem.io", "cinemeta-catalogs.strem.io", "watchhub.strem.io",
    "yastream.tamthai.de", "movie-addon.vercel.app", "mediafusion.elfhosted.com",
    "stremio-addons.net", "web.stremio.com", "test-streams.mux.dev",
}


def java_urlencode(value: str) -> str:
    """Java URLEncoder-compatible encoding for the characters used in topics."""
    out = []
    for byte in value.encode("utf-8"):
        char = chr(byte)
        if char.isalnum() or char in "-._*":
            out.append(char)
        elif char == " ":
            out.append("+")
        else:
            out.append(f"%{byte:02X}")
    return "".join(out)


def parse_engines() -> list[dict[str, Any]]:
    text = ENGINES_FILE.read_text(encoding="utf-8")
    blocks = re.findall(r"EngineSpec\((.*?)\n\s*\)", text, re.S)
    out: list[dict[str, Any]] = []
    for block in blocks:
        kind_match = re.search(r"kind\s*=\s*PipelineKind\.(\w+)", block)
        if not kind_match:
            continue

        def string_value(key: str, default: str = "") -> str:
            match = re.search(rf'{key}\s*=\s*"([^"]*)"', block)
            return match.group(1) if match else default

        def int_value(key: str, default: int) -> int:
            match = re.search(rf"{key}\s*=\s*(\d+)", block)
            return int(match.group(1)) if match else default

        parser_match = re.search(r"parser\s*=\s*EngineParser\.(\w+)", block)
        out.append({
            "id": string_value("id"),
            "kind": kind_match.group(1),
            "family": string_value("family"),
            "topic": string_value("topic"),
            "urlTemplate": string_value("urlTemplate"),
            "parser": parser_match.group(1) if parser_match else "",
            "maxPages": int_value("maxPages", 20),
            "pageSize": int_value("pageSize", 100),
        })
    return out


def host_of(raw: str) -> str:
    value = raw.strip().lower()
    if value.startswith("//"):
        value = "https:" + value
    if not value.startswith("http://") and not value.startswith("https://"):
        return ""
    value = value.substringAfter("://") if hasattr(value, "substringAfter") else value.split("://", 1)[1]
    value = value.split("/", 1)[0].split("?", 1)[0].split("#", 1)[0]
    value = value.split(":", 1)[0]
    if value.startswith("www."):
        value = value[4:]
    return value.rstrip(".")


def origin_of(raw: str) -> str:
    host = host_of(raw)
    return f"https://{host}" if host else ""


def path_of(raw: str) -> str:
    value = raw.strip()
    if value.startswith("//"):
        value = "https:" + value
    if not value.startswith("http://") and not value.startswith("https://"):
        return "/"
    after = value.split("://", 1)[1]
    slash = after.find("/")
    if slash < 0:
        return "/"
    return after[slash:].split("?", 1)[0].split("#", 1)[0]


def clean_url(raw: str) -> str:
    value = raw.strip().rstrip(",;)]}'\"").replace("\\/", "/")
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme in ("http", "https") and parsed.query:
        for part in parsed.query.split("&"):
            key, _, encoded = part.partition("=")
            if key.lower() in REDIRECT_KEYS and encoded:
                try:
                    decoded = urllib.parse.unquote_plus(encoded)
                except Exception:
                    decoded = encoded
                if decoded.startswith("http://") or decoded.startswith("https://"):
                    return decoded.strip().rstrip(",;)]}'\"")
    return value


def absolute(raw: str, referer: str) -> str:
    value = clean_url(raw)
    if value.startswith("http://") or value.startswith("https://"):
        return value
    if value.startswith("//"):
        return "https:" + value
    base = origin_of(referer)
    if value.startswith("/"):
        return base + value
    return base + "/" + value


def engine_hosts(engines: list[dict[str, Any]]) -> set[str]:
    return {host_of(engine["urlTemplate"]) for engine in engines}


def parse_engine(engine: dict[str, Any], body: str) -> list[str]:
    cleaned = body.replace("\\/", "/")
    base = origin_of(engine["urlTemplate"])
    urls: list[str] = []
    seen: set[str] = set()

    def add(raw: str) -> None:
        value = clean_url(raw)
        if value.startswith("http") and host_of(value) and value not in seen:
            seen.add(value)
            urls.append(value)

    if engine["parser"] in ("URL_LINES", "JSON_URLS"):
        for match in URL_RE.findall(cleaned):
            add(match)
    elif engine["parser"] == "HTML_LINKS":
        for raw in HREF_RE.findall(cleaned):
            raw = raw.strip()
            if raw.startswith(("javascript:", "#", "mailto:")):
                continue
            add(absolute(raw, engine["urlTemplate"]))
        for match in URL_RE.findall(cleaned):
            add(match)
    elif engine["parser"] == "HOST_LINES":
        for match in HOST_RE.findall(cleaned):
            add(f"https://{match.lower()}/")
    elif engine["parser"] == "MANIFEST_JSON":
        for match in MANIFEST_RE.findall(cleaned):
            add(match)
    return [value for value in urls if origin_of(value) != base or path_of(value) != "/"]


def suffix(host: str, values: list[str]) -> bool:
    return any(host == value or host.endswith("." + value) for value in values)


def garbage_reason(url: str, engine_hosts: set[str]) -> str:
    host = host_of(url)
    path = path_of(url).lower()
    lower = url.lower()
    if not host:
        return "empty-host"
    if PRIVATE_HOST_RE.match(host) or IPV4_RE.match(host):
        return "private-or-ip-host"
    if "." not in host or len(host) < 4:
        return "not-a-public-domain"
    if host.rsplit(".", 1)[-1] < "aa" or len(host.rsplit(".", 1)[-1]) < 2:
        return "invalid-tld"
    if host in engine_hosts:
        return "public-index-host"
    if suffix(host, AD_HOSTS):
        return "ad-or-tracker-host"
    if suffix(host, IRRELEVANT_HOSTS):
        return "irrelevant-index-cdn-or-corporate-host"
    if JUNK_PATH_RE.search(path):
        return "cms-ad-or-tracker-path"
    if ASSET_PATH_RE.search(path):
        return "static-asset-not-site"
    if "magnet:" in lower or "xt=urn:btih" in lower:
        return "torrent-not-site"
    return ""


def sorter_score(url: str, topic: str, provenance: str) -> int:
    host = host_of(url)
    path = path_of(url).lower()
    topic_lower = topic.lower()
    score = 20
    if any(word in topic_lower for word in STRONGER_WORDS):
        score += 12
    if any(word in host for word in MEDIA_WORDS):
        score += 10
    if any(word in path for word in MEDIA_WORDS):
        score += 8
    if any(word in provenance for word in ("crtsh", "wayback", "urlscan", "github", "npm", "pypi", "reddit")):
        score += 4
    if "manifest.json" in path or "/catalog/" in path or "/stream/" in path:
        score += 10
    return score


def github_slot() -> None:
    """GitHub search has a low secondary rate limit; serialise its requests."""
    global _GITHUB_LAST_REQUEST
    with _GITHUB_LOCK:
        now = time.monotonic()
        wait = GITHUB_MIN_INTERVAL_SECONDS - (now - _GITHUB_LAST_REQUEST)
        if wait > 0:
            time.sleep(wait)
        _GITHUB_LAST_REQUEST = time.monotonic()


def fetch(url: str, timeout: float, profile: str, max_bytes: int = 1_000_000) -> dict[str, Any]:
    host = host_of(url)
    if profile == "local" and host not in LOCAL_ALLOWED_HOSTS:
        return {"url": url, "status": 0, "body": "", "contentType": "", "error": "network-blocked-by-sandbox"}
    headers = {"User-Agent": USER_AGENT, "Accept": "*/*"}
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if host == "api.github.com" and token:
        headers["Authorization"] = f"Bearer {token}"
    attempts = 2 if host == "api.github.com" else 1
    for attempt in range(attempts):
        if host == "api.github.com":
            github_slot()
        request = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body = response.read(max_bytes).decode("utf-8", errors="ignore")
                return {
                    "url": response.geturl(),
                    "status": response.status,
                    "body": body,
                    "contentType": response.headers.get("content-type", ""),
                    "error": "",
                }
        except urllib.error.HTTPError as error:
            body = error.read(max_bytes).decode("utf-8", errors="ignore")
            retryable = (
                host == "api.github.com" and error.code == 403
                and "rate limit" in body.lower() and attempt + 1 < attempts
            )
            if retryable:
                time.sleep(10)
                continue
            return {"url": url, "status": error.code, "body": body, "contentType": error.headers.get("content-type", ""), "error": f"http-{error.code}"}
        except Exception as error:
            return {"url": url, "status": 0, "body": "", "contentType": "", "error": f"{type(error).__name__}: {error}"}
    return {"url": url, "status": 0, "body": "", "contentType": "", "error": "github-retry-exhausted"}


def json_item_count(body: str) -> int:
    """Count real JSON catalogue/stream items, not just HTML hrefs."""
    text = body.strip()
    if not text or text[0] not in "[{":
        return 0
    try:
        payload = json.loads(text)
    except Exception:
        return 0

    def largest(node: Any) -> int:
        if isinstance(node, list):
            return len(node)
        if isinstance(node, dict):
            best = 0
            for key in ("metas", "streams", "addons", "results", "items"):
                value = node.get(key)
                if isinstance(value, list):
                    best = max(best, len(value))
            for value in node.values():
                if isinstance(value, (list, dict)):
                    best = max(best, largest(value))
            return best
        return 0

    return largest(payload)


def catalogue_item_count(html: str) -> int:
    items: set[str] = set()
    for raw in HREF_RE.findall(html):
        value = clean_url(raw)
        if value.startswith("http://") or value.startswith("https://"):
            path = path_of(value)
        elif value.startswith("//"):
            path = path_of("https:" + value)
        elif value.startswith("/"):
            path = value.split("?", 1)[0].split("#", 1)[0]
        else:
            path = "/" + value
        if CATALOGUE_PATH_RE.search(path) and not JUNK_PATH_RE.search(path):
            items.add(path.lower())
    return max(len(items), json_item_count(html))


def extract_sources(kind: str, html: str, network: list[str], referer: str) -> list[dict[str, str]]:
    observed: list[str] = []
    for raw in ATTR_URL_RE.findall(html):
        observed.append(absolute(raw, referer))
    for raw in URL_RE.findall(html):
        observed.append(absolute(raw, referer))
    observed.extend(absolute(raw, referer) for raw in network)
    out: list[dict[str, str]] = []
    seen: set[str] = set()
    for url in observed:
        reason = garbage_reason(url, set())
        if reason:
            continue
        path = path_of(url).lower()
        if kind == "ADDON":
            if path.endswith("manifest.json") or path.endswith("/manifest"):
                media = "manifest"
            elif "/stream/" in path or "/stream.json" in path:
                media = "stream"
            elif "/catalog/" in path or "/catalog.json" in path:
                media = "catalog"
            else:
                media = "unknown"
        else:
            if path.endswith(".m3u8") or "mpegurl" in path:
                media = "hls"
            elif path.endswith(".mpd") or "dash+xml" in path:
                media = "dash"
            elif path.endswith(".mp4") or "video/" in path:
                media = "mp4"
            elif API_PATH_RE.search(path):
                media = "embed-api"
            else:
                media = "unknown"
        if media != "unknown" and url not in seen:
            seen.add(url)
            out.append({"url": url, "mediaKind": media})
    return out


def media_kind(url: str, content_type: str) -> str:
    path = path_of(url).lower()
    ctype = content_type.lower()
    if path.endswith("manifest.json") or path.endswith("/manifest"):
        return "manifest"
    if "/stream/" in path or "/stream.json" in path:
        return "stream-json"
    if "/catalog/" in path or "/catalog.json" in path:
        return "catalog-json"
    if path.endswith(".m3u8") or "mpegurl" in ctype:
        return "hls"
    if path.endswith(".mpd") or "dash+xml" in ctype:
        return "dash"
    if path.endswith(".mp4") or "video/mp4" in ctype or "video/" in ctype:
        return "mp4"
    if "application/json" in ctype or path.endswith(".json"):
        return "json"
    if "text/html" in ctype:
        return "page"
    return "unknown"


def look_candidate(candidate: dict[str, Any], timeout: float, profile: str) -> dict[str, Any]:
    kind = candidate["kind"]
    origin = candidate["url"]
    errors: list[str] = []
    html_parts: list[str] = []
    network: list[str] = []
    title_matched = False
    search_errors = 0
    json_catalogue_count = 0
    endpoints: list[tuple[str, str]] = []

    if kind == "WEBSITE":
        home = fetch(origin, timeout, profile)
        if home["error"] or not 200 <= home["status"] < 300:
            errors.append("home:" + (home["error"] or f"http-{home['status']}"))
        else:
            html_parts.append(home["body"])
        search_url = origin.rstrip("/") + "/?s=" + java_urlencode("The Shawshank Redemption")
        search = fetch(search_url, timeout, profile)
        if search["error"] or not 200 <= search["status"] < 300:
            search_errors += 1
            errors.append("search:" + (search["error"] or f"http-{search['status']}"))
        else:
            html_parts.append(search["body"])
            title_matched = "The Shawshank Redemption" in search["body"]
    else:
        root = origin.rstrip("/")
        manifest_url = root + "/manifest.json"
        manifest_response = fetch(manifest_url, timeout, profile)
        manifest_body = ""
        if manifest_response["error"] or not 200 <= manifest_response["status"] < 300:
            errors.append("manifest:" + (manifest_response["error"] or f"http-{manifest_response['status']}"))
        else:
            manifest_body = manifest_response["body"]
            html_parts.append(manifest_body)
            network.append(manifest_url)

        try:
            manifest = json.loads(manifest_body) if manifest_body else {}
        except Exception:
            manifest = {}
        if not isinstance(manifest, dict):
            manifest = {}
        resources: set[str] = set()
        for item in manifest.get("resources", []):
            if isinstance(item, str):
                resources.add(item.lower())
            elif isinstance(item, dict) and isinstance(item.get("name"), str):
                resources.add(item["name"].lower())
        types = [item for item in manifest.get("types", []) if isinstance(item, str)]
        media_type = "movie" if "movie" in types or not types else types[0]
        for item in manifest.get("catalogs", [])[:3]:
            if not isinstance(item, dict):
                continue
            catalog_type = item.get("type") if isinstance(item.get("type"), str) else media_type
            catalog_id = item.get("id")
            if isinstance(catalog_id, str) and catalog_id:
                endpoints.append((f"{root}/catalog/{catalog_type}/{catalog_id}.json", "catalog"))
        if not endpoints and "catalog" in resources:
            endpoints.append((f"{root}/catalog/{media_type}/top.json", "catalog"))
        # One bounded movie id proves stream behaviour without six requests.
        if "stream" in resources:
            endpoints.append((f"{root}/stream/movie/tt0111161.json", "stream"))

        for endpoint, label in endpoints:
            response = fetch(endpoint, timeout, profile)
            if response["error"] or not 200 <= response["status"] < 300:
                search_errors += 1
                errors.append("endpoint:" + (response["error"] or f"http-{response['status']}"))
                continue
            html_parts.append(response["body"])
            network.append(endpoint)
            if label == "catalog":
                count = json_item_count(response["body"])
                json_catalogue_count = max(json_catalogue_count, count)
                if count > 0 or '"metas"' in response["body"]:
                    title_matched = True
            elif '"streams"' in response["body"] or "tt0111161" in response["body"]:
                title_matched = True

    combined = "\n".join(html_parts)
    count = max(catalogue_item_count(combined), json_catalogue_count)
    sources = extract_sources(kind, combined, network, origin)
    has_catalogue = count >= (20 if kind == "ADDON" else 40)
    has_server = bool(sources)
    searchable = title_matched and search_errors == 0 and not errors
    classification = "ok" if (
        not errors and searchable and has_catalogue and has_server
    ) else ";".join(errors + ([f"catalogue-{count}"] if count < (20 if kind == "ADDON" else 40) else []) + ([] if searchable else ["not-searchable"]) + ([] if sources else ["no-server"]))
    probe: dict[str, Any] | None = None
    verified = False
    if classification == "ok" and sources:
        priority = ["stream", "stream-json", "manifest", "catalog"] if kind == "ADDON" else ["hls", "dash", "mp4", "embed-api"]
        source = next((item for wanted in priority for item in sources if item["mediaKind"] == wanted), None)
        if source:
            probe = fetch(source["url"], timeout, profile, max_bytes=8192)
            kind_of_probe = media_kind(probe["url"], probe.get("contentType", ""))
            verified = (
                not probe["error"] and 200 <= probe["status"] < 300
                and bool(probe["body"]) and kind_of_probe not in ("unknown", "page")
                and not TORRENT_RE.search(probe["url"] + probe["body"][:200])
            )
    return {
        "kind": kind,
        "url": origin,
        "host": candidate["host"],
        "engineId": candidate["engineId"],
        "family": candidate["family"],
        "catalogueItemCount": count,
        "sourceCount": len(sources),
        "sources": [item["url"] for item in sources[:5]],
        "endpoints": [url for url, _ in endpoints],
        "searchable": searchable,
        "classification": classification,
        "probeStatus": probe["status"] if probe else 0,
        "probeKind": media_kind(probe["url"], probe.get("contentType", "")) if probe else "",
        "verified": verified,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Bounded real crawl probe for Nova finder engines")
    parser.add_argument("--profile", choices=["local", "ci"], default="local")
    parser.add_argument("--round", type=int, default=0)
    parser.add_argument("--max-look", type=int, default=12)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--output", default="")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()

    engines = parse_engines()
    modifier = QUERY_MODIFIERS[args.round % len(QUERY_MODIFIERS)]
    requests_list: list[dict[str, Any]] = []
    for engine in engines:
        query = " ".join(part for part in (engine["topic"], modifier) if part)
        encoded = java_urlencode(query)
        url = (engine["urlTemplate"]
               .replace("{query}", encoded)
               .replace("{page}", "1")
               .replace("{offset}", "0"))
        requests_list.append({**engine, "url": url})

    if args.profile == "local":
        selected = [item for item in requests_list if host_of(item["url"]) in LOCAL_ALLOWED_HOSTS]
        skipped = [item for item in requests_list if host_of(item["url"]) not in LOCAL_ALLOWED_HOSTS]
    else:
        selected = requests_list
        skipped = []

    fetched: list[dict[str, Any]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        future_map = {pool.submit(fetch, item["url"], args.timeout, args.profile, 3_000_000): item for item in selected}
        for future in concurrent.futures.as_completed(future_map):
            item = future_map[future]
            response = future.result()
            fetched.append({**item, **{k: response[k] for k in ("url", "status", "body", "contentType", "error")}})

    fetched.sort(key=lambda item: item["id"])
    hosts = engine_hosts(engines)
    candidates: list[dict[str, Any]] = []
    candidate_seen: set[str] = set()
    rejection_counts: dict[str, int] = {}
    for item in fetched:
        if item["error"] or not 200 <= item["status"] < 300:
            continue
        for url in parse_engine(item, item["body"]):
            host = host_of(url)
            origin = f"https://{host}/" if host else ""
            key = f"{item['kind']}|{host}"
            if not host or key in candidate_seen:
                continue
            candidate_seen.add(key)
            reason = garbage_reason(origin or url, hosts)
            if reason:
                rejection_counts[reason] = rejection_counts.get(reason, 0) + 1
                continue
            candidates.append({
                "url": origin,
                "host": host,
                "kind": item["kind"],
                "engineId": item["id"],
                "family": item["family"],
                "topic": item["topic"],
                "provenance": item["family"],
                "score": sorter_score(origin, item["topic"], item["family"]),
            })

    # Stable, publicly indexed endpoints first, then by sorter score. This does
    # not whitelist them: each one still has to answer the same live checks.
    candidates.sort(key=lambda item: (0 if item["host"] in PREFERRED_HOSTS else 1, -item["score"], item["host"]))

    looked: list[dict[str, Any]] = []
    look_stop_reason = "no-look-requested" if args.max_look <= 0 else "no-candidates"
    if args.max_look > 0 and candidates:
        ordered = candidates[: args.max_look]
        batch_size = max(1, min(32, args.workers * 2))
        verification_target = 3
        for offset in range(0, len(ordered), batch_size):
            batch = ordered[offset:offset + batch_size]
            with concurrent.futures.ThreadPoolExecutor(max_workers=min(4, args.workers)) as pool:
                future_map = {pool.submit(look_candidate, candidate, args.timeout, args.profile): candidate for candidate in batch}
                for future in concurrent.futures.as_completed(future_map):
                    looked.append(future.result())
            if sum(1 for item in looked if item["verified"]) >= verification_target:
                look_stop_reason = f"verified-{verification_target}-sites"
                break
        else:
            look_stop_reason = "candidate-budget-exhausted"
    looked.sort(key=lambda item: (not item["verified"], item["kind"], item["host"]))

    responses_ok = sum(1 for item in fetched if not item["error"] and 200 <= item["status"] < 300)
    verified_sites = [item for item in looked if item["verified"]]
    ci_verification_required = args.profile == "ci"
    assertions = {
        "engines_selected": len(selected) >= (30 if args.profile == "local" else 65),
        "responses_ok": responses_ok >= (10 if args.profile == "local" else 25),
        "candidates": len(candidates) >= 1,
        "sorter_accepted": len(candidates) >= 1,
        "verified_for_ci": (len(verified_sites) >= 1) if ci_verification_required else None,
    }
    applicable_assertions = {key: value for key, value in assertions.items() if value is not None}
    ok = all(applicable_assertions.values())
    result = {
        "ok": ok,
        "generated_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "profile": args.profile,
        "round": args.round,
        "engines_total": len(engines),
        "engines_selected": len(selected),
        "engines_skipped": len(skipped),
        "responses_ok": responses_ok,
        "responses_failed": sum(1 for item in fetched if item["error"] or not 200 <= item["status"] < 300),
        "candidates": len(candidates),
        "sorter_acceptances": len(candidates),
        "rejection_counts": rejection_counts,
        "looked": len(looked),
        "verified": len(verified_sites),
        "verified_sites": verified_sites,
        "candidate_samples": candidates[:25],
        "candidate_hosts": [item["host"] for item in candidates],
        "look_stop_reason": look_stop_reason,
        "response_samples": [
            {
                "id": item["id"],
                "family": item["family"],
                "url": item["url"],
                "status": item["status"],
                "bytes": len(item["body"].encode("utf-8")),
                "error": item["error"],
                "candidateUrls": len(parse_engine(item, item["body"])) if not item["error"] and 200 <= item["status"] < 300 else 0,
            }
            for item in fetched
        ],
        "look_samples": looked[:25],
        "ci_verification_required": ci_verification_required,
        "verified_for_ci": assertions["verified_for_ci"],
        "assertions": assertions,
        "errors": [] if ok else [name for name, passed in applicable_assertions.items() if not passed],
    }
    payload = {"ok": ok, "tool": "crawl_probe", "result": result}
    if args.json or not args.output:
        print(json.dumps(payload, indent=2, sort_keys=True))
    if args.output:
        out = Path(args.output)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if ok:
        print("NOVA CRAWL PROBE PASS", file=sys.stderr)
    else:
        print(f"NOVA CRAWL PROBE FAIL: {result['errors']}", file=sys.stderr)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
