"""Cinema catalogue client — Stremio-addon compatible, no API key required.

The addon protocol (manifest.json + /catalog/{type}/{id}.json) is what Stremio,
WuPlay, Nuvio and ARVIO all speak, so one client covers every feed that matters.
Parsing is defensive: a feed is untrusted input and a malformed field must drop
one item, not the whole catalogue.
"""
from __future__ import annotations

import urllib.parse
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional

from .http import Cache, FetchError, fetch_json

CINEMETA = "https://v3-cinemeta.strem.io"
# Several deployments of the same catalogues exist; a blocked or retired host
# should not take the whole catalogue down.
FEED_HOSTS = [
    "https://v3-cinemeta.strem.io",
    "https://cinemeta-catalogs.strem.io",
    "https://v3-cinemeta.strem.io/",
]
KNOWN_FEEDS = {
    "cinemeta": {"manifest": f"{CINEMETA}/manifest.json", "name": "Cinemeta"},
    "top": {"manifest": "https://cinemeta-catalogs.strem.io/manifest.json", "name": "Cinemeta Top"},
}


@dataclass
class Title:
    id: str
    type: str
    name: str
    poster: Optional[str] = None
    background: Optional[str] = None
    year: Optional[str] = None
    rating: Optional[float] = None
    description: str = ""
    genres: List[str] = field(default_factory=list)
    imdb_id: Optional[str] = None
    tmdb_id: Optional[int] = None

    def as_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "type": self.type,
            "name": self.name,
            "poster": self.poster,
            "background": self.background,
            "year": self.year,
            "rating": self.rating,
            "description": self.description,
            "genres": self.genres,
            "imdb_id": self.imdb_id,
            "tmdb_id": self.tmdb_id,
        }


def _as_float(value: Any) -> Optional[float]:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def parse_meta(raw: Dict[str, Any]) -> Optional[Title]:
    """Map one Stremio meta object to a Title, or None if it is unusable."""
    if not isinstance(raw, dict):
        return None
    name = raw.get("name") or raw.get("title")
    identifier = raw.get("imdb_id") or raw.get("id")
    if not name or not identifier:
        return None
    kind = raw.get("type") or "movie"
    imdb_id = raw.get("imdb_id") or (identifier if str(identifier).startswith("tt") else None)
    tmdb_id = raw.get("moviedb_id") or raw.get("tmdb_id")
    try:
        tmdb_id = int(tmdb_id) if tmdb_id is not None else None
    except (TypeError, ValueError):
        tmdb_id = None
    genres = raw.get("genres") or raw.get("genre") or []
    if isinstance(genres, str):
        genres = [part.strip() for part in genres.split(",") if part.strip()]
    return Title(
        id=str(identifier),
        type="tv" if kind in ("series", "tv", "show") else "movie",
        name=str(name),
        poster=raw.get("poster"),
        background=raw.get("background") or raw.get("backdrop"),
        year=str(raw.get("year") or "")[:4] or None,
        rating=_as_float(raw.get("imdbRating") or raw.get("vote_average")),
        description=(raw.get("description") or raw.get("overview") or "")[:4000],
        genres=[str(g) for g in genres][:12],
        imdb_id=imdb_id,
        tmdb_id=tmdb_id,
    )


def fetch_catalog(
    base: str = CINEMETA,
    catalog_type: str = "movie",
    catalog_id: str = "top",
    *,
    cache: Optional[Cache] = None,
    timeout: float = 20.0,
) -> List[Title]:
    """GET {base}/catalog/{type}/{id}.json and map the metas."""
    url = f"{base.rstrip('/')}/catalog/{catalog_type}/{catalog_id}.json"
    payload = fetch_json(url, cache=cache, timeout=timeout)
    metas = payload.get("metas") if isinstance(payload, dict) else None
    if not isinstance(metas, list):
        raise FetchError(f"{url} did not return a metas array")
    titles = [parse_meta(item) for item in metas]
    return [title for title in titles if title is not None]


def reachable_hosts(*, timeout: float = 8.0) -> List[Dict[str, Any]]:
    """Probe each feed host and report reachability instead of raising."""
    from .http import FetchError
    report = []
    for host in dict.fromkeys(FEED_HOSTS):
        try:
            manifest = fetch_manifest(host, timeout=timeout)
            report.append({"host": host, "ok": True, "name": manifest.get("name")})
        except (FetchError, OSError) as error:
            report.append({"host": host, "ok": False, "error": str(error)[:160]})
    return report


def fetch_manifest(base: str, *, cache: Optional[Cache] = None, timeout: float = 20.0) -> Dict[str, Any]:
    payload = fetch_json(f"{base.rstrip('/')}/manifest.json", cache=cache, timeout=timeout)
    if not isinstance(payload, dict):
        raise FetchError("manifest is not an object")
    catalogs = payload.get("catalogs")
    if catalogs is not None and not isinstance(catalogs, list):
        raise FetchError("manifest catalogs is not a list")
    return payload


def merge_feeds(titles: Iterable[Title]) -> List[Title]:
    """De-duplicate by (type, imdb/tmdb id), keeping the best-rated copy."""
    best: Dict[str, Title] = {}
    for title in titles:
        key = f"{title.type}:{title.imdb_id or title.tmdb_id or title.id}"
        current = best.get(key)
        if current is None or (title.rating or 0) > (current.rating or 0):
            best[key] = title
    return sorted(best.values(), key=lambda t: (t.rating or 0), reverse=True)


def search_local(titles: Iterable[Title], query: str, limit: int = 40) -> List[Title]:
    """Case-insensitive substring search, ranked by prefix match then rating."""
    needle = query.strip().lower()
    if not needle:
        return []
    scored = []
    for title in titles:
        haystack = title.name.lower()
        if needle in haystack:
            scored.append((0 if haystack.startswith(needle) else 1, -(title.rating or 0), title))
    scored.sort(key=lambda row: (row[0], row[1]))
    return [title for _, _, title in scored[:limit]]


def stream_probe_urls(base: str, meta: Title) -> List[str]:
    """The /stream endpoints worth asking for one title, in preference order."""
    identifier = meta.imdb_id or meta.id
    kind = "series" if meta.type == "tv" else "movie"
    return [
        f"{base.rstrip('/')}/stream/{kind}/{urllib.parse.quote(identifier)}.json",
    ]
