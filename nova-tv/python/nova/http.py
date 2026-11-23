"""HTTP with retries, backoff, caching and a hard size cap.

Deliberately uses only the standard library so the tooling runs anywhere with a
bare Python 3.11+, including CI images that install nothing.

Safety properties this module is responsible for:
  * redirects are followed only for http/https and never to a private address
    (a catalogue feed is attacker-controlled input)
  * responses are size-capped so a hostile feed cannot exhaust memory
  * 429/5xx are retried with exponential backoff and jitter
"""
from __future__ import annotations

import hashlib
import ipaddress
import json
import random
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, Optional

MAX_BYTES = 8 * 1024 * 1024
RETRY_STATUS = {408, 425, 429, 500, 502, 503, 504}


class FetchError(RuntimeError):
    """A request failed in a way the caller should see."""


@dataclass
class Response:
    url: str
    status: int
    headers: Dict[str, str] = field(default_factory=dict)
    body: bytes = b""
    from_cache: bool = False

    @property
    def text(self) -> str:
        return self.body.decode("utf-8", errors="replace")

    def json(self) -> Any:
        return json.loads(self.text)


def _is_public_host(host: str) -> bool:
    """Reject literal private/loopback/link-local addresses."""
    if not host:
        return False
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        return False
    for info in infos:
        addr = info[4][0]
        try:
            ip = ipaddress.ip_address(addr)
        except ValueError:
            return False
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved:
            return False
    return True


class _SafeRedirects(urllib.request.HTTPRedirectHandler):
    """Follow redirects only between public http(s) hosts."""

    max_redirections = 5

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401
        parsed = urllib.parse.urlparse(newurl)
        if parsed.scheme not in ("http", "https"):
            raise FetchError(f"refusing redirect to scheme {parsed.scheme!r}")
        if not _is_public_host(parsed.hostname or ""):
            raise FetchError(f"refusing redirect to non-public host {parsed.hostname!r}")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _opener() -> urllib.request.OpenerDirector:
    return urllib.request.build_opener(_SafeRedirects())


class Cache:
    """Tiny on-disk cache keyed by URL + accept header."""

    def __init__(self, directory: Path, ttl: float = 300.0):
        self.directory = directory
        self.ttl = ttl

    def _path(self, key: str) -> Path:
        digest = hashlib.sha256(key.encode("utf-8")).hexdigest()[:32]
        return self.directory / f"{digest}.json"

    def get(self, key: str) -> Optional[Response]:
        path = self._path(key)
        if not path.exists():
            return None
        try:
            payload = json.loads(path.read_text("utf-8"))
        except (OSError, ValueError):
            return None
        if time.time() - payload.get("stored_at", 0) > self.ttl:
            return None
        return Response(
            url=payload["url"],
            status=payload["status"],
            headers=payload.get("headers", {}),
            body=payload.get("body", "").encode("utf-8"),
            from_cache=True,
        )

    def put(self, key: str, response: Response) -> None:
        try:
            self.directory.mkdir(parents=True, exist_ok=True)
            payload = {
                "stored_at": time.time(),
                "url": response.url,
                "status": response.status,
                "headers": response.headers,
                "body": response.body.decode("utf-8", errors="replace"),
            }
            self._path(key).write_text(json.dumps(payload), "utf-8")
        except OSError:
            pass


def fetch(
    url: str,
    *,
    headers: Optional[Dict[str, str]] = None,
    timeout: float = 20.0,
    retries: int = 3,
    user_agent: str = "NovaTV/1.0",
    cache: Optional[Cache] = None,
    method: str = "GET",
    data: Optional[bytes] = None,
    allow_private: bool = False,
) -> Response:
    """Fetch a URL with retries. Raises :class:`FetchError` on final failure."""
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise FetchError(f"unsupported scheme {parsed.scheme!r}")
    if not allow_private and not _is_public_host(parsed.hostname or ""):
        raise FetchError(f"refusing non-public host {parsed.hostname!r}")

    cache_key = f"{method} {url} {json.dumps(headers or {}, sort_keys=True)}"
    if cache is not None and method == "GET":
        hit = cache.get(cache_key)
        if hit is not None:
            return hit

    request_headers = {"User-Agent": user_agent, "Accept": "*/*"}
    request_headers.update(headers or {})

    last_error: Optional[Exception] = None
    opener = _opener()
    for attempt in range(1, max(1, retries) + 1):
        request = urllib.request.Request(url, data=data, headers=request_headers, method=method)
        try:
            with opener.open(request, timeout=timeout) as handle:
                body = handle.read(MAX_BYTES + 1)
                if len(body) > MAX_BYTES:
                    raise FetchError(f"response from {url} exceeds {MAX_BYTES} bytes")
                response = Response(
                    url=handle.geturl(),
                    status=handle.status,
                    headers={k.lower(): v for k, v in handle.headers.items()},
                    body=body,
                )
            if cache is not None and method == "GET":
                cache.put(cache_key, response)
            return response
        except urllib.error.HTTPError as error:
            last_error = error
            if error.code not in RETRY_STATUS or attempt == max(1, retries):
                raise FetchError(f"HTTP {error.code} for {url}") from error
        except (urllib.error.URLError, TimeoutError, socket.timeout) as error:
            last_error = error
            if attempt == max(1, retries):
                raise FetchError(f"network error for {url}: {error}") from error
        # exponential backoff with jitter, so a fleet of clients does not sync up
        sleep_for = min(8.0, 0.4 * (2 ** (attempt - 1))) * (0.6 + random.random() * 0.8)
        time.sleep(sleep_for)

    raise FetchError(f"giving up on {url}: {last_error}")


def fetch_json(url: str, **kwargs: Any) -> Any:
    return fetch(url, **kwargs).json()


def head_ok(url: str, *, timeout: float = 8.0) -> bool:
    """Cheap reachability probe used by the source health checks."""
    try:
        fetch(url, timeout=timeout, retries=1, method="GET")
        return True
    except FetchError:
        return False


def dedupe(urls: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for url in urls:
        if url and url not in seen:
            seen.add(url)
            ordered.append(url)
    return ordered
