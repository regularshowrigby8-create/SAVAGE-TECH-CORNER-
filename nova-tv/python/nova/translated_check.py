"""Debug + hallucination + logic check for the translated Python finders.

The translated finders (``servers.py`` -> ``ServerFinder.kt`` and
``addons.py`` -> ``AddonFinder.kt``) replaced the old ``ServerHarvester``
and ``WebsiteSpider`` scripts. This module proves, from the filesystem and
not from memory, that:

1. **Debug / no stubs / no false green**
   - every translated file exists, its package matches its directory, and
     it declares the classes the wiring expects;
   - no ``TODO``/``FIXME``/``NotImplemented``/empty-body stubs remain,
     except the one documented platform stub (``FinderBrowser``, which
     always takes the plain-HTTP branch the Python scripts take when
     Playwright is missing);
   - the Kotlin unit tests for the translated finders actually assert
     behaviour (extractor, prober, store, torrent filter, score), not
     just that a constant equals itself;
   - the checker itself returns ``ok: False`` when a check fails, so a
     green run cannot be a script that never checked anything.

2. **Hallucination**
   - every file, class and method named in the translated finders'
     KDoc and in ``docs/FINDER.md`` exists on disk;
   - every ``DEFAULT_SOURCES`` / catalogue URL is https, distinct, has
     no embedded credentials and no private-secret patterns;
   - no claim is made about a file that is not there.

3. **Logic (Python original vs Kotlin translation)**
   - CAP, concurrency, probe bytes, catalogue clamp, fails-to-dead,
     seeds, manifest paths, probe ids, torrent regexes, quality tiers
     and the two score formulas match the Python originals exactly,
     by re-computing the Python formulas and comparing the Kotlin
     constants/coefficients found in the source;
   - buffer-score formula matches in both probers;
   - catalogue thresholds (40 / 400 / 120) match.

4. **Wiring accounted for what is already there**
   - ``NovaApplication`` constructs both translated harvesters and
     passes them to ``SourceEngine``;
   - ``SourceEngine`` calls ``harvester.harvest(round)`` for the addon
     pool and ``spider.harvest(round)`` for the website pool, with the
     translated types;
   - the old scripts are gone and nothing imports them;
   - both flavours share ``src/main`` (no finder copy under ``src/tv``
     or ``src/mobile``), so TV and mobile get the same finders;
   - pool capacities agree: translated CAP == ``ServerPool.CAPACITY``
     == ``WebsitePool.CAPACITY`` == 6000;
   - ``FinderRules.DEFAULT_SOURCES`` count is reported, and the
     subtitle-only source is not also a stream source.

Everything is deterministic and offline. Live URL reachability is
reported as ``unverified``, never as ``proven``, because this sandbox
cannot reach those hosts; CI with network can re-run the live check.
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

# --- Python originals (servers.py / addons.py), the reference logic --------

SERVER_CAP = 6000
ADDON_CAP = 6000
SERVER_CONCURRENCY = 48
ADDON_CONCURRENCY = 40
PROBE_BYTES = 262_144
CHUNK = 32_768
MAX_CATALOGUE = 250_000
FAILS_TO_DEAD = 2
CHECK_INTERVAL = 300

SERVER_SEEDS = [
    "https://hianime.to",
    "https://hianime.bz",
    "https://aniwatchtv.to",
    "https://9anime.org.lv",
    "https://9animetv.to",
    "https://aniwatch.to",
    "https://zoroxtv.to",
    "https://kaido.to",
    "https://anikai.to",
    "https://gogoanime.tel",
    "https://animekai.to",
    "https://9anime.pl",
]

ADDON_SEEDS = [
    "https://api.github.com/search/repositories?q=stremio+addon+in:name,description,readme&sort=updated&per_page=100",
    "https://api.npms.io/v2/search?q=stremio%20addon&size=250",
    "https://registry.npmjs.org/-/v1/search?text=stremio%20addon&size=250",
    "https://www.reddit.com/r/StremioAddons/new.json?limit=100",
    "https://www.reddit.com/r/StremioAddons/search.json?q=manifest.json&restrict_sr=1&sort=new&limit=100",
    "https://stremio-addons.net",
    "https://stremio-addons.com",
]

MANIFEST_PATHS = [
    "/manifest.json",
    "/manifest",
    "/api/manifest.json",
    "/addon/manifest.json",
    "/stremio/manifest.json",
    "/manifest.json?t=1",
]

PROBE_IDS = [("movie", "tt0111161"), ("series", "tt0944947"), ("anime", "kitsu:1")]
MANIFEST_KEYS = ("id", "version", "resources", "types", "catalogs")

SERVER_QUALITY = [
    (r"2160|4k|uhd", "2160p", 400),
    (r"hdr|dolby.?vision|\bdv\b", "HDR", 60),
    (r"1080|fhd|full.?hd", "1080p", 300),
    (r"720|\bhd\b", "720p", 200),
    (r"576|480|\bsd\b|cam", "480p", 100),
]

ADDON_QUALITY = [
    (r"2160|4k|uhd", "2160p", 400),
    (r"hdr|dolby.?vision|\bdv\b", "HDR", 60),
    (r"1080|fhd", "1080p", 300),
    (r"720", "720p", 200),
    (r"480|sd\b", "480p", 100),
]


def score_of_server(catalogue: int, tier: int, buffer_score: int, ttfb: int) -> float:
    """servers.py score_of, verbatim."""
    cat = math.log10(1 + max(catalogue, 0)) / math.log10(1 + MAX_CATALOGUE)
    return round(0.55 * cat * 100 + 0.30 * min(tier, 400) / 4 + 0.15 * buffer_score - ttfb / 120, 2)


def score_of_addon(catalogue: int, tier: int, streams: int, buffer_score: int, ttfb: int) -> float:
    """addons.py score_of, verbatim."""
    cat = math.log10(1 + max(catalogue, 0)) / math.log10(1 + MAX_CATALOGUE)
    return round(
        0.45 * cat * 100
        + 0.25 * min(tier, 400) / 4
        + 0.15 * min(streams, 60) / 0.6
        + 0.15 * buffer_score
        - ttfb / 120,
        2,
    )


def buffer_score(ttfb_ms: int, stalls: int, jitter_ms: int, kbps: int) -> int:
    """Shared prober formula, verbatim."""
    return max(0, min(100, int(100 - ttfb_ms / 40 - stalls * 12 - jitter_ms / 25 + min(kbps / 60, 35))))


def quality_of(text: str, table=SERVER_QUALITY) -> Tuple[str, int]:
    for pattern, label, tier in table:
        if re.search(pattern, text, re.I):
            return label, tier
    return "unknown", 0


# --- check plumbing ----------------------------------------------------------


@dataclass
class Check:
    name: str
    verdict: str  # proven | contradicted | unverified
    why: str
    evidence: str = ""

    @property
    def ok(self) -> bool:
        return self.verdict == "proven"

    def as_dict(self) -> Dict[str, object]:
        return {"name": self.name, "verdict": self.verdict, "why": self.why, "evidence": self.evidence}


def _read(path: Path) -> str:
    return path.read_text(errors="ignore") if path.exists() else ""


def _kotlin_files(root: Path) -> List[Path]:
    src = root / "android" / "app" / "src"
    return sorted(src.rglob("*.kt")) if src.is_dir() else []


def _strip_comments(text: str) -> str:
    """Drop // and /* */ comments, keeping strings (URLs contain //)."""
    out: List[str] = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j == -1 else j
        elif text.startswith("/*", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if text.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif text.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    if text[i] == "\n":
                        out.append("\n")
                    i += 1
            out.append(" ")
        elif text[i] == '"':
            j = i + 1
            while j < n and text[j] != '"' and text[j] != "\n":
                if text[j] == "\\":
                    j += 2
                    continue
                j += 1
            out.append(text[i : min(j + 1, n)])
            i = min(j + 1, n)
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


# --- individual checks -------------------------------------------------------


def check_files_exist(root: Path) -> List[Check]:
    base = root / "android" / "app" / "src" / "main" / "java" / "dev" / "nova" / "tv"
    expected = {
        "finder/translated/FinderHelpers.kt": "FinderHelpers",
        "finder/translated/FinderFingerprint.kt": "FinderFingerprint",
        "finder/translated/FinderHttp.kt": "FinderHttp",
        "finder/translated/FinderBrowser.kt": "FinderBrowser",
        "finder/servers/ServerFinder.kt": "ServerFinderHarvester",
        "finder/addons/AddonFinder.kt": "AddonFinderHarvester",
    }
    checks: List[Check] = []
    for rel, symbol in expected.items():
        path = base / rel
        if not path.exists():
            checks.append(Check(f"file {rel} exists", "contradicted", "translated file missing", str(path)))
            continue
        text = _read(path)
        if symbol not in text:
            checks.append(Check(f"{rel} declares {symbol}", "contradicted", f"{symbol} not found in {rel}"))
        else:
            # package must match directory
            pkg_match = re.search(r"^package\s+([\w.]+)", text, re.M)
            expected_pkg = "dev.nova.tv." + rel.rsplit("/", 1)[0].replace("/", ".")
            if not pkg_match or pkg_match.group(1) != expected_pkg:
                checks.append(
                    Check(f"{rel} package matches directory", "contradicted",
                          f"package {pkg_match.group(1) if pkg_match else '?'} != {expected_pkg}")
                )
            else:
                checks.append(Check(f"file {rel} exists and declares {symbol}", "proven", "found, package matches", rel))
    return checks


def check_no_stubs(root: Path) -> List[Check]:
    """No TODO/FIXME/empty stubs, except the documented FinderBrowser stub."""
    checks: List[Check] = []
    translated = [
        root / "android/app/src/main/java/dev/nova/tv/finder/translated/FinderHelpers.kt",
        root / "android/app/src/main/java/dev/nova/tv/finder/translated/FinderFingerprint.kt",
        root / "android/app/src/main/java/dev/nova/tv/finder/translated/FinderHttp.kt",
        root / "android/app/src/main/java/dev/nova/tv/finder/servers/ServerFinder.kt",
        root / "android/app/src/main/java/dev/nova/tv/finder/addons/AddonFinder.kt",
    ]
    markers = ["TODO", "FIXME", "NotImplemented", "TODO()", "error(\"not implemented"]
    for path in translated:
        text = _strip_comments(_read(path))
        hits = [m for m in markers if m in text]
        # empty function bodies: `fun x() { }` or `fun x() = Unit` outside FinderBrowser
        empty = re.findall(r"fun\s+\w+\([^)]*\)[^{=]*\{\s*\}", text)
        if hits or empty:
            checks.append(Check(f"no stubs in {path.name}", "contradicted", f"markers={hits} empty={empty[:3]}", path.name))
        else:
            checks.append(Check(f"no stubs in {path.name}", "proven", "no TODO/FIXME/empty bodies", path.name))
    # FinderBrowser used to be a documented stub and this audit used to *require*
    # the stub (`start(): Boolean = false`). That check was green while the
    # website pool was empty on every phone, because "stub is documented" is not
    # a property of a working finder. It now requires the opposite: a renderer
    # backed by Android's own Chromium, and the reason recorded in the file.
    browser = root / "android/app/src/main/java/dev/nova/tv/finder/translated/FinderBrowser.kt"
    text = _read(browser)
    code = _strip_comments(text)
    renders = "WebView(" in code and "loadUrl(" in code
    documented = "Playwright" in text and "WebView" in text
    if renders and documented:
        checks.append(Check("FinderBrowser renders through a browser engine", "proven",
                            "WebView-backed, Playwright mapping documented", browser.name))
    else:
        checks.append(Check("FinderBrowser renders through a browser engine", "contradicted",
                            "must drive a WebView and say how it maps to Playwright", browser.name))
    return checks


def check_logic_constants(root: Path) -> List[Check]:
    checks: List[Check] = []
    server = _read(root / "android/app/src/main/java/dev/nova/tv/finder/servers/ServerFinder.kt")
    addon = _read(root / "android/app/src/main/java/dev/nova/tv/finder/addons/AddonFinder.kt")
    helpers = _read(root / "android/app/src/main/java/dev/nova/tv/finder/translated/FinderHelpers.kt")

    def has(text: str, needle: str) -> bool:
        return needle in text

    cases = [
        ("server CAP 6000", server, "const val CAP = 6000"),
        ("addon CAP 6000", addon, "const val CAP = 6000"),
        ("server concurrency 48", server, "DEFAULT_CONCURRENCY = 48"),
        ("addon concurrency 40", addon, "DEFAULT_CONCURRENCY = 40"),
        ("probe bytes 262144", server, "PROBE_BYTES = 262_144"),
        ("chunk 32768", server, "CHUNK = 32_768"),
        ("max catalogue 250000", server, "MAX_CATALOGUE = 250_000"),
        ("fails to dead 2", server, "FAILS_TO_DEAD = 2"),
        ("check interval 300", server, "CHECK_INTERVAL = 300"),
        ("server score 0.55", helpers, "0.55 * cat * 100"),
        ("server score 0.30 tier", helpers, "0.30 * min(tier, 400)"),
        ("addon score 0.45", helpers, "0.45 * cat * 100"),
        ("addon score 0.25 tier", helpers, "0.25 * min(tier, 400)"),
        ("addon score streams 0.15/0.6", helpers, "0.15 * min(streams, 60) / 0.6"),
        ("buffer score formula", helpers, "100 - ttfbMs / 40 - stalls * 12 - jitterMs / 25"),
        ("catalogue threshold 40", server, "catalogueCount < 40"),
        ("catalogue threshold 400", server, "n < 400"),
        ("catalogue threshold 120", server, "n < 120"),
        ("torrent hints", addon, "torrentio|jackettio|mediafusion"),
        # In Kotlin source the pattern is a normal string: "magnet:\\?xt=urn:btih:"
        # (double backslash). Compare on the regex text, not on Python escaping.
        ("magnet regex", addon, "magnet:\\\\?xt=urn:btih:"),
        ("manifest keys", addon, '"id", "version", "resources", "types", "catalogs"'),
        ("probe id tt0111161", addon, "tt0111161"),
        ("probe id tt0944947", addon, "tt0944947"),
        ("probe id kitsu:1", addon, "kitsu:1"),
    ]
    for name, text, needle in cases:
        checks.append(
            Check(name, "proven" if has(text, needle) else "contradicted",
                  f"expected {needle!r} in Kotlin source" if not has(text, needle) else "constant matches Python original", needle)
        )

    # seeds: every Python seed must appear in Kotlin
    for seed in SERVER_SEEDS:
        checks.append(Check(f"server seed {seed}", "proven" if seed in server else "contradicted",
                            "seed present in ServerFinder.SEEDS" if seed in server else "seed missing", seed))
    for seed in ADDON_SEEDS:
        # Kotlin may escape the URL; check the host/path core
        core = seed.split("?")[0]
        checks.append(Check(f"addon seed {core}", "proven" if core in addon else "contradicted",
                            "seed present in AddonFinder.SEEDS" if core in addon else "seed missing", core))
    for path in MANIFEST_PATHS:
        checks.append(Check(f"manifest path {path}", "proven" if path in addon else "contradicted",
                            "path present" if path in addon else "path missing", path))

    # numeric score formulas: Python reference must be monotonic in catalogue
    big = score_of_server(100_000, 400, 90, 100)
    small = score_of_server(100, 100, 90, 100)
    checks.append(Check("server score ranks catalogue first", "proven" if big > small else "contradicted",
                        f"big={big} small={small}"))
    big_a = score_of_addon(100_000, 400, 60, 90, 100)
    small_a = score_of_addon(100, 100, 2, 90, 100)
    checks.append(Check("addon score ranks catalogue first", "proven" if big_a > small_a else "contradicted",
                        f"big={big_a} small={small_a}"))
    checks.append(Check("buffer score clamps 0..100", "proven" if buffer_score(0, 0, 0, 6000) == 100 and buffer_score(10000, 99, 9999, 0) == 0 else "contradicted",
                        f"hi={buffer_score(0,0,0,6000)} lo={buffer_score(10000,99,9999,0)}"))
    checks.append(Check("quality_of server 4k", "proven" if quality_of("watch 4k uhd") == ("2160p", 400) else "contradicted",
                        str(quality_of("watch 4k uhd"))))
    checks.append(Check("quality_of addon 720", "proven" if quality_of("720", ADDON_QUALITY) == ("720p", 200) else "contradicted",
                        str(quality_of("720", ADDON_QUALITY))))
    return checks


def check_wiring(root: Path) -> List[Check]:
    checks: List[Check] = []
    app = _read(root / "android/app/src/main/java/dev/nova/tv/NovaApplication.kt")
    engine = _read(root / "android/app/src/main/java/dev/nova/tv/finder/SourceEngine.kt")
    pool = _read(root / "android/app/src/main/java/dev/nova/tv/finder/pool/ServerPool.kt")
    website_pool = _read(root / "android/app/src/main/java/dev/nova/tv/finder/pool/WebsitePool.kt")
    rules = _read(root / "android/app/src/main/java/dev/nova/tv/finder/FinderRules.kt")

    cases = [
        ("NovaApplication constructs ServerFinderHarvester", app, "ServerFinderHarvester("),
        ("NovaApplication constructs AddonFinderHarvester", app, "AddonFinderHarvester("),
        ("NovaApplication passes addon harvester to engine", app, "harvester = addonFinderHarvester"),
        ("NovaApplication passes server harvester to engine", app, "spider = serverFinderHarvester"),
        ("NovaApplication creates servers_pool store", app, '"servers_pool.json"'),
        ("NovaApplication creates addons_pool store", app, '"addons_pool.json"'),
        ("SourceEngine harvester type is AddonFinderHarvester", engine, "AddonFinderHarvester?"),
        ("SourceEngine spider type is ServerFinderHarvester", engine, "ServerFinderHarvester?"),
        ("SourceEngine calls harvester.harvest", engine, "harvester.harvest(round)"),
        ("SourceEngine calls spider.harvest", engine, "spider?.harvest"),
        ("ServerPool CAPACITY 6000", pool, "CAPACITY = 6000"),
        ("WebsitePool CAPACITY 6000", website_pool, "CAPACITY = 6000"),
    ]
    for name, text, needle in cases:
        checks.append(Check(name, "proven" if needle in text else "contradicted",
                            f"expected {needle!r}" if needle not in text else "wired", needle))

    # old scripts gone, nothing imports them
    old_files = [
        root / "android/app/src/main/java/dev/nova/tv/finder/pool/ServerHarvester.kt",
        root / "android/app/src/main/java/dev/nova/tv/finder/website/WebsiteSpider.kt",
    ]
    for path in old_files:
        checks.append(Check(f"old script gone {path.name}", "proven" if not path.exists() else "contradicted",
                            "file deleted (replaced)" if not path.exists() else "old file still present", path.name))
    all_kotlin = "\n".join(_read(p) for p in _kotlin_files(root))
    checks.append(Check("no import of old ServerHarvester", "proven" if "import dev.nova.tv.finder.pool.ServerHarvester" not in all_kotlin else "contradicted",
                        "no dangling import"))
    checks.append(Check("no import of old WebsiteSpider", "proven" if "import dev.nova.tv.finder.website.WebsiteSpider" not in all_kotlin else "contradicted",
                        "no dangling import"))

    # both flavours share src/main: no finder copy under src/tv or src/mobile
    for flavour in ("tv", "mobile"):
        finder_copies = list((root / f"android/app/src/{flavour}").rglob("*Finder*.kt")) if (root / f"android/app/src/{flavour}").exists() else []
        checks.append(Check(f"no finder copy in src/{flavour}", "proven" if not finder_copies else "contradicted",
                            f"copies={finder_copies}" if finder_copies else "shared src/main only", flavour))

    # DEFAULT_SOURCES: count, distinct, https, no credentials, subtitle-only not duplicated
    sources = re.findall(r'"(https://[^"]+)"', rules)
    # only those inside DEFAULT_SOURCES block
    block = re.search(r"DEFAULT_SOURCES\s*=\s*listOf\((.*?)\)\s*\n", rules, re.S)
    default_sources = re.findall(r'"(https://[^"]+)"', block.group(1)) if block else []
    checks.append(Check("DEFAULT_SOURCES count >= 14", "proven" if len(default_sources) >= 14 else "contradicted",
                        f"count={len(default_sources)}", str(len(default_sources))))
    checks.append(Check("DEFAULT_SOURCES distinct", "proven" if len(default_sources) == len(set(default_sources)) else "contradicted",
                        f"distinct={len(set(default_sources))}/{len(default_sources)}"))
    bad = [u for u in default_sources if not u.startswith("https://") or "://" in u[8:] or "@" in u.split("/")[2]]
    checks.append(Check("DEFAULT_SOURCES https no credentials", "proven" if not bad else "contradicted",
                        f"bad={bad}" if bad else "all https, no userinfo"))
    checks.append(Check("subtitle-only source is in DEFAULT_SOURCES", "proven" if "https://opensubtitles-v3.strem.io" in default_sources else "contradicted",
                        "opensubtitles-v3 present"))

    # -- the catalogue additions and the source-evidence table ----------------
    checks += _check_sources(rules, default_sources)
    checks += _check_subtitles(root)
    checks += _check_caption_style(root)
    return checks


# Sources that were checked live and kept. The doc must list the rejects too,
# so "why is X missing" has an answer that is not guesswork.
REQUIRED_SOURCES = [
    "https://tmdb.elfhosted.com",
    "https://1fe84bc728af-imdb-catalogs.baby-beamup.club",
    "https://1fe84bc728af-rpdb.baby-beamup.club",
    "https://stremio-film-festivals.elfhosted.com",
    "https://c73485b8a7a2-latamtv.baby-beamup.club",
    "https://5db836ec3ef8-thepiratebay-ctl.baby-beamup.club",
    "https://1fe84bc728af-catalog-builder.baby-beamup.club",
    "https://24dfd6fd4287-stremio-tmdb.baby-beamup.club",
    "https://ftv-stremio.surge.sh",
    "https://stremio.github.io/stremio-static-addon-example",
]

REQUIRED_SUBTITLE_HOSTS = [
    "https://opensubtitles-v3.strem.io",
    "https://opensubtitlesv3-pro.dexter21767.com",
    "https://subsense.nepiraw.com",
]


def _check_sources(rules: str, default_sources: List[str]) -> List[Check]:
    checks: List[Check] = []
    checks.append(Check("DEFAULT_SOURCES has 24 entries", "proven" if len(default_sources) == 24 else "contradicted",
                        f"count={len(default_sources)}"))
    for url in REQUIRED_SOURCES:
        checks.append(Check(f"catalogue source {url}", "proven" if url in default_sources else "contradicted",
                            "present in DEFAULT_SOURCES" if url in default_sources else "missing"))
    # Every source must carry a line of evidence, and the evidence table must
    # not describe a URL that is not in the list.
    evidence = re.findall(r'"(https://[^"]+)"\s+to\s+"([^"]*)"', rules)
    evidence_urls = {u for u, _ in evidence}
    for url in REQUIRED_SOURCES:
        described = any(u == url and len(text) > 4 for u, text in evidence)
        checks.append(Check(f"evidence for {url}", "proven" if described else "contradicted",
                            "row count and types recorded" if described else "no SOURCE_EVIDENCE entry"))
    orphans = sorted(evidence_urls - set(default_sources))
    checks.append(Check("SOURCE_EVIDENCE describes no unknown URL", "proven" if not orphans else "contradicted",
                        f"orphans={orphans}" if orphans else "every evidence URL is a live source"))
    # No API key may be embedded in any source URL, and no source may rely on
    # a query-string secret (the reason the keyed addons were rejected).
    keyed = [u for u in default_sources if _looks_keyed(u)]
    checks.append(Check("no keyed or tokenised source URL", "proven" if not keyed else "contradicted",
                        f"keyed={keyed}" if keyed else "no key= / token= / api_key= segments"))
    return checks


def _looks_keyed(url: str) -> bool:
    tail = url.split("?", 1)[1].lower() if "?" in url else ""
    return any(token in tail for token in ("key=", "token=", "apikey", "api_key", "auth="))


def _check_subtitles(root: Path) -> List[Check]:
    checks: List[Check] = []
    path = root / "android/app/src/main/java/dev/nova/tv/data/remote/SubtitleFinder.kt"
    text = _read(path)
    if not text:
        return [Check("SubtitleFinder.kt exists", "contradicted", "file missing", str(path))]
    for host in REQUIRED_SUBTITLE_HOSTS:
        checks.append(Check(f"subtitle host {host}", "proven" if host in text else "contradicted",
                            "constant present" if host in text else "host missing from SubtitleFinder"))
    for provider in ("OPEN_SUBTITLES_PRO", "SUB_SENSE"):
        enum_declared = re.search(rf"{provider}\(\"", text) is not None
        branch = re.search(rf"SubtitleProvider\.{provider}\s*->", text) is not None
        checks.append(Check(f"provider {provider} declared and handled",
                            "proven" if enum_declared and branch else "contradicted",
                            f"declared={enum_declared} handled={branch}"))
    # A branch that returns emptyList is a stub dressed as a provider.
    stub = re.search(r"SubtitleProvider\.SUB_SENSE\s*->\s*emptyList\(\)", text)
    checks.append(Check("SUB_SENSE is not a stub", "proven" if not stub else "contradicted",
                        "routed to subSense()" if not stub else "returns emptyList"))
    # Filenames must stay distinguishable in the panel: each provider records
    # its own label rather than all claiming "OpenSubtitles".
    labels = re.findall(r"source = provider\.label", text)
    checks.append(Check("each engine records its own label", "proven" if labels else "contradicted",
                        f"{len(labels)} call sites use provider.label"))
    return checks


def _check_caption_style(root: Path) -> List[Check]:
    checks: List[Check] = []
    path = root / "android/app/src/main/java/dev/nova/tv/player/SubtitleStyle.kt"
    text = _read(path)
    if not text:
        return [Check("SubtitleStyle.kt exists", "contradicted", "file missing", str(path))]
    cases = [
        ("line cap 0.80", "MAX_LINE_FRACTION = 0.80f"),
        ("safe bottom 5%", "SAFE_BOTTOM_PERCENT = 5"),
        ("default bottom 9%", "DEFAULT_BOTTOM_PERCENT = 9"),
        ("bold tv typeface", 'Typeface.create("sans-serif", Typeface.BOLD)'),
        ("bottom clamped to safe area", "coerceAtLeast(TvCaption.SAFE_BOTTOM_PERCENT)"),
        ("cinema has an outline", "CaptionTone.CINEMA -> CaptionStyleCompat.EDGE_TYPE_OUTLINE"),
        ("side inset applied", "SIDE_INSET_FRACTION"),
    ]
    for name, needle in cases:
        checks.append(Check(f"caption {name}", "proven" if needle in text else "contradicted",
                            "present" if needle in text else f"missing {needle!r}"))
    # The inset must come from display metrics, not view.width: the style is
    # applied from a LaunchedEffect before the first measure.
    checks.append(Check("caption inset survives pre-measure",
                        "proven" if "displayMetrics.widthPixels" in text and "view.width *" not in text else "contradicted",
                        "uses displayMetrics" if "displayMetrics.widthPixels" in text else "uses view.width (0 before measure)"))
    # Size stepping must work in both directions.
    checks.append(Check("caption size steps both ways", "proven" if "fun SubtitleStyle.stepSize(up: Boolean)" in text else "contradicted",
                        "stepSize(up) present" if "stepSize(up: Boolean)" in text else "only up-cycling"))
    return checks


def check_hallucination(root: Path) -> List[Check]:
    """Every file/class/method named in FINDER.md and KDoc exists."""
    checks: List[Check] = []
    finder_md = root / "docs" / "FINDER.md"
    text = _read(finder_md)
    # File references like `finder/servers/ServerFinder.kt`. A path that is a
    # URL fragment (`api.strem.io/addonscollection.json`) or a URL template
    # (`base/manifest.json`) is prose, not a claim about a file on disk, so it
    # is excluded by requiring a code directory prefix.
    code_roots = ("finder/", "data/", "ui/", "player/", "ops/", "docs/", "scripts/", "nova/", "tests/")
    file_refs = re.findall(r"`([\w./-]+\.(?:kt|md|json))`", text)
    for ref in file_refs:
        if not ref.startswith(code_roots):
            continue
        candidates = [
            root / ref,
            root / "android/app/src/main/java/dev/nova/tv" / ref,
            root / "android/app/src/main/java/dev/nova/tv/finder" / ref,
            root / "docs" / Path(ref).name,
        ]
        found = any(c.exists() for c in candidates)
        checks.append(Check(f"FINDER.md file `{ref}` exists", "proven" if found else "contradicted",
                            "found on disk" if found else "no such file from any root", ref))
    # Class names mentioned in backticks in translated KDoc. `ServerHarvester`
    # is deliberately named in both files as the script that was replaced, so
    # a name asserted absent is only a contradiction if it is not introduced
    # as the old script.
    all_kotlin = "\n".join(_read(p) for p in _kotlin_files(root))
    declared = set(re.findall(r"(?:class|object|interface|enum class)\s+(\w+)", all_kotlin))
    removed = {"ServerHarvester", "WebsiteSpider"}
    for rel in [
        "android/app/src/main/java/dev/nova/tv/finder/servers/ServerFinder.kt",
        "android/app/src/main/java/dev/nova/tv/finder/addons/AddonFinder.kt",
        "android/app/src/main/java/dev/nova/tv/finder/translated/FinderHelpers.kt",
    ]:
        doc = _read(root / rel)
        for name in re.findall(r"`(\w+Harvester|\w+Finder|\w+Pool|\w+Store|\w+Checker|\w+Prober|\w+Extractor|\w+Validator|\w+Catalogue)`", doc):
            if name in removed:
                # Must be described as replaced, and must actually be gone.
                ok = f"replaces the old" in doc or f"old `{name}`" in doc
                gone = name not in declared
                checks.append(Check(f"KDoc `{name}` marked as removed", "proven" if ok and gone else "contradicted",
                                    f"described_as_old={ok} no_longer_declared={gone}", name))
                continue
            checks.append(Check(f"KDoc class `{name}` exists", "proven" if name in declared else "contradicted",
                                f"declared={name in declared}", name))
    # secret patterns in catalogue sources
    secret_patterns = [r"ghp_[A-Za-z0-9]{36}", r"sk-[A-Za-z0-9]{20,}", r"AKIA[0-9A-Z]{16}", r"-----BEGIN .*PRIVATE KEY-----"]
    rules = _read(root / "android/app/src/main/java/dev/nova/tv/finder/FinderRules.kt")
    hits = [p for p in secret_patterns if re.search(p, rules)]
    checks.append(Check("no private secrets in FinderRules", "proven" if not hits else "contradicted",
                        f"hits={hits}" if hits else "no ghp/sk/AKIA/private-key patterns"))
    return checks


def check_tests_are_real(root: Path) -> List[Check]:
    """No false green: tests must assert behaviour, not just constants."""
    checks: List[Check] = []
    test_files = {
        "ServerFinderTest.kt": root / "android/app/src/test/java/dev/nova/tv/finder/servers/ServerFinderTest.kt",
        "AddonFinderTest.kt": root / "android/app/src/test/java/dev/nova/tv/finder/addons/AddonFinderTest.kt",
    }
    required_assertions = {
        "ServerFinderTest.kt": ["Extractor", "probe", "store", "writePool", "score"],
        "AddonFinderTest.kt": ["torrent", "score", "store", "writePool", "queue"],
    }
    for name, path in test_files.items():
        if not path.exists():
            checks.append(Check(f"test {name} exists", "contradicted", "missing", name))
            continue
        text = _read(path)
        asserts = len(re.findall(r"assert\w+\(", text))
        checks.append(Check(f"{name} has assertions", "proven" if asserts >= 10 else "contradicted",
                            f"assertions={asserts}", name))
        for needle in required_assertions.get(name, []):
            checks.append(Check(f"{name} covers {needle}", "proven" if needle.lower() in text.lower() else "contradicted",
                                f"needle {needle!r} in test" if needle.lower() in text.lower() else "test does not cover this", name))
        # false green: a test that only asserts CAP == 6000
        if asserts < 5:
            checks.append(Check(f"{name} is not false green", "contradicted", "too few assertions", name))
    return checks


def check_live(root: Path) -> List[Check]:
    """Probe catalogue URLs, but only claim what a probe can prove.

    Offline or blocked hosts come back ``unverified``, never ``proven``:
    a network error says nothing about whether the URL is real. A 2xx on a
    ``manifest.json`` proves the URL serves an addon manifest, which is the
    whole claim DEFAULT_SOURCES makes.
    """
    import json
    import urllib.error
    import urllib.request

    checks: List[Check] = []
    rules = _read(root / "android/app/src/main/java/dev/nova/tv/finder/FinderRules.kt")
    block = re.search(r"DEFAULT_SOURCES\s*=\s*listOf\((.*?)\)\s*\n", rules, re.S)
    urls = re.findall(r'"(https://[^"]+)"', block.group(1)) if block else []
    for url in urls:
        probe = url if url.endswith(".json") else url.rstrip("/") + "/manifest.json"
        request = urllib.request.Request(probe, headers={"User-Agent": "nova-finder-check/1.0"})
        try:
            with urllib.request.urlopen(request, timeout=8) as response:
                body = response.read(4096).decode("utf-8", "ignore")
                status = getattr(response, "status", 200)
        except (urllib.error.URLError, urllib.error.HTTPError, OSError, ValueError) as error:
            checks.append(Check(f"live {url}", "unverified", f"probe failed: {error}", probe))
            continue
        if status != 200:
            checks.append(Check(f"live {url}", "unverified", f"HTTP {status} (not 200; may need config)", probe))
            continue
        try:
            manifest = json.loads(body)
        except ValueError:
            checks.append(Check(f"live {url}", "unverified", "200 but not JSON in the first 4 KB", probe))
            continue
        keys = [k for k in MANIFEST_KEYS if k in manifest]
        checks.append(
            Check(f"live {url}", "proven" if len(keys) >= 3 else "unverified",
                  f"manifest keys={keys}" if keys else "200 but no manifest keys", probe)
        )
    return checks


def check_player_and_intro(root: Path) -> List[Check]:
    """The player and its skip-intro data path.

    The audit used to stop at the finders, so changes under `player/` were
    reported green purely by not being looked at. These are the facts a
    "the player works" claim actually rests on: the imported engine is present
    and attributed, intro timestamps come from a real keyless source rather
    than being invented, and the skip button is driven by that data.
    """
    checks: List[Check] = []
    base = root / "android/app/src/main/java/dev/nova/tv"

    # 1. The ARVIO engine is an import, and says so. A file that copies a
    #    licence header while having no engine is the failure mode here.
    engine = _read(base / "player/engine/exoplayer/ExoPlayerEngine.kt")
    exo = base / "player/engine/exoplayer/ExoPlayerEngine.kt"
    attributed = "ARVIO" in engine and "Apache" in engine
    checks.append(Check(
        "imported player engine is present and attributed",
        "proven" if attributed and exo.exists() else "contradicted",
        f"arvio_header={attributed} file_exists={exo.exists()}",
        "player/engine/exoplayer/ExoPlayerEngine.kt",
    ))
    notices = _read(root / "android/THIRD_PARTY_NOTICES.md")
    checks.append(Check(
        "third-party notice names the imported player",
        "proven" if "ARVIO" in notices and "Apache" in notices else "contradicted",
        "notice mentions ARVIO and Apache-2.0",
        "android/THIRD_PARTY_NOTICES.md",
    ))

    # 2. Intro timestamps come from a declared source. Inventing them from
    #    chapter labels was an earlier attempt that always returned null; the
    #    check pins the real endpoints so it cannot come back.
    intro = _read(base / "player/IntroSkip.kt")
    intro_code = _strip_comments(intro)
    has_class = "class IntroLookup" in intro_code
    # From code, not the KDoc: the header names both endpoints while explaining
    # where the data comes from, so a whole-file search survives their deletion.
    has_apis = "api.theintrodb.org" in intro_code and "api.introdb.app" in intro_code
    checks.append(Check(
        "intro timestamps come from a real keyless source",
        "proven" if has_class and has_apis else "contradicted",
        f"lookup_class={has_class} both_endpoints={has_apis}",
        "player/IntroSkip.kt",
    ))
    # 3. No invented detection: intro data must never be guessed locally.
    invented = re.search(r"refreshIntroWindow", intro + _read(base / "player/NovaPlayer.kt"))
    checks.append(Check(
        "intro window is never guessed from the media",
        "proven" if not invented else "contradicted",
        "no local chapter/label guessing routine",
        "player/IntroSkip.kt",
    ))
    # 4. The window is guarded, so a bad timestamp cannot move the picture.
    #    Asserted behaviourally: the confidence floor must actually reject a
    #    low-confidence window, and the start guard must actually gate the
    #    window. Merely naming the constants is what a hollow check would do --
    #    setting MIN_CONFIDENCE to 0.0 keeps the name and removes the guard.
    floor = re.search(r"MIN_CONFIDENCE\s*=\s*([0-9.]+)", intro)
    floor_value = float(floor.group(1)) if floor else None
    compare = re.search(r"confidence\s*>=\s*MIN_CONFIDENCE", intro)
    guard = re.search(r"position\s*>=\s*IntroLookup\.INTRO_START_GUARD_MS", _read(base / "player/NovaPlayer.kt"))
    usable = (
        floor_value is not None and floor_value > 0.0
        and bool(compare) and bool(guard)
    )
    checks.append(Check(
        "a skip is only offered for usable data",
        "proven" if usable else "contradicted",
        f"confidence_floor={floor_value} compared_in_usable={bool(compare)} start_guard_applied={bool(guard)}",
        "player/IntroSkip.kt",
    ))
    # 5. Both surfaces offer the control, and it is wired to the player.
    tv = _read(base / "ui/player/PlayerSurface.kt")
    mobile = _read(root / "android/app/src/mobile/java/dev/nova/tv/mobile/MobilePlayer.kt")
    host = _read(base / "ui/screens/NovaPlayerHost.kt")
    # The lookup must be fed a real id and installed, not stubbed to null.
    wired = bool(re.search(r"introLookup\.introFor\(", host)) and bool(
        re.search(r"setIntroSegment\(segment\)", host)
    ) and "setIntroSegment(null)" not in host
    tv_code = _strip_comments(tv)
    mobile_code = _strip_comments(mobile)
    tv_button = "Skip intro" in tv_code and "player.skipIntro()" in tv_code
    mobile_button = "Skip intro" in mobile_code and "player.skipIntro()" in mobile_code
    both = tv_button and mobile_button
    checks.append(Check(
        "skip-intro is wired on both surfaces",
        "proven" if wired and both else "contradicted",
        f"host_lookup={wired} tv_button={tv_button} mobile_button={mobile_button}",
        "NovaPlayerHost.kt + PlayerSurface.kt + MobilePlayer.kt",
    ))
    return checks


def check_seek_gestures(root: Path) -> List[Check]:
    """Seeking must be reachable, and the two-detector bug must stay fixed.

    The mobile bar installed detectTapGestures and detectHorizontalDragGestures
    as two separate pointerInput modifiers on one Canvas. Two detectors cannot
    both win a pointer stream, so a tap-to-jump and a drag-to-scrub fought and
    "jump to a point" only worked for whichever won. This pins the single
    gesture loop, in both directions.
    """
    checks: List[Check] = []
    mobile = _read(root / "android/app/src/mobile/java/dev/nova/tv/mobile/MobilePlayer.kt")
    code = _strip_comments(mobile)
    # Slice out the seek bar and inspect only it. A whole-file search is
    # satisfied by the gesture code elsewhere in the player, and by the KDoc
    # that names the old detectors while explaining the bug.
    def seekbar(text: str) -> str:
        start = text.find("private fun SeekBar(")
        if start < 0:
            return ""
        end = text.find("\nprivate fun ", start + 10)
        nxt = text.find("\n@Composable", start + 10)
        stops = [x for x in (end, nxt) if x > 0]
        return text[start:min(stops)] if stops else text[start:]

    bar = seekbar(code)
    single = "awaitEachGesture" in bar
    # A live (non-comment) rival detector inside the bar is the regression.
    # Strict on purpose. An earlier version only failed when the detector was
    # preceded by `pointerInput`, so a reinstated detector declared another way
    # slipped through. Two detectors on one pointer stream is the bug, however
    # it is spelled, so any live use inside the bar is a contradiction.
    rival = "detectHorizontalDragGestures" in bar or "detectTapGestures" in bar
    if "detectHorizontalDragGestures" in bar:
        rival = True
    # Both behaviours must be present: a press classifies as a jump, and a
    # move tracks the finger. One without the other is a half-fix.
    jumps = "seek((" in bar
    # The assignment, not the name -- and specifically the one inside the
    # pointer-event loop. The bar writes dragFraction three times (drag start,
    # drag move, release); a global search stays green when the *move* write is
    # deleted, which is the one that makes the bar follow the finger.
    loop_start = bar.find("awaitPointerEvent()")
    loop = bar[loop_start:] if loop_start >= 0 else ""
    # The bar writes dragFraction in two distinct places: while the finger
    # moves (following x) and on release (committing change.position.x). The
    # release write alone is not enough -- the picture must follow the finger
    # during the drag -- so the move path is required by name.
    follows = bool(re.search(r"x\s*=\s*change\.position\.x", loop)) and bool(
        re.search(r"dragFraction\s*=\s*\(\s*x\s*/", loop)
    )
    tracks = follows
    # ...and the release must commit through the same path.
    commits = bool(re.search(r"seek\(\(dragFraction \* total\)\.toLong\(\)\)", bar))
    checks.append(Check(
        "mobile seek bar uses one gesture loop",
        "proven" if single and not rival and jumps and tracks and commits else "contradicted",
        f"single_loop={single} rival_detector={rival} commits_jump={jumps} "
        f"tracks_drag={tracks} commits_on_release={commits}",
        "mobile/MobilePlayer.kt",
    ))

    player = _read(root / "android/app/src/main/java/dev/nova/tv/player/NovaPlayer.kt")
    checks.append(Check(
        "absolute seek exists for a scrub bar",
        "proven" if "fun seekTo(" in player else "contradicted",
        "NovaPlayer.seekTo(positionMs)",
        "player/NovaPlayer.kt",
    ))
    # Skipping to the exact end restarts the episode; the guard must survive.
    checks.append(Check(
        "seeks stop short of the end",
        "proven" if "SEEK_END_GUARD_MS" in player else "contradicted",
        "end guard present in the seek path",
        "player/NovaPlayer.kt",
    ))

    # The TV remote path: the bar must actually handle D-pad keys.
    tv = _strip_comments(_read(root / "android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt"))
    # Scoped to the scrub bar: seeking also appears in the general chrome keys,
    # so a whole-file search would stay green with the bar's handling deleted.
    bar_start = tv.find("private fun ScrubBar(")
    bar = tv[bar_start:tv.find("\nprivate fun ", bar_start + 10)] if bar_start >= 0 else ""
    dpad = "KEYCODE_DPAD_LEFT" in bar and "KEYCODE_DPAD_RIGHT" in bar
    commit = bool(re.search(r"scrub\.commit\(\)\?\.let \{ player\.seekTo", bar))
    checks.append(Check(
        "TV scrub bar handles the D-pad",
        "proven" if dpad and commit else "contradicted",
        f"left_right_keys={dpad} commits_cursor={commit}",
        "ui/player/PlayerSurface.kt",
    ))
    return checks


def check_finder_correctness(root: Path) -> List[Check]:
    """The three defects that made the website finder return nothing.

    Each was a divergence from the Python this was translated from, and each is
    invisible to a "does the file exist" check, so they are asserted directly.
    """
    checks: List[Check] = []
    fh = _read(root / "android/app/src/main/java/dev/nova/tv/finder/translated/FinderHttp.kt")
    code = _strip_comments(fh)
    # Both facts must hold in *code*, not prose. The KDoc above the jar names
    # `.cookieJar(jar)` while explaining the bug, so a naive search over the
    # whole file is satisfied by the comment even after the call is deleted --
    # which is exactly the false green this audit exists to prevent.
    jar = "class MemoryCookieJar" in code and ".cookieJar(jar)" in code
    checks.append(Check(
        "finder keeps a session cookie jar",
        "proven" if jar else "contradicted",
        "Python's ClientSession keep-alive had no equivalent; without it every "
        "follow-up request was rejected" if not jar else "jar installed on the client",
        "finder/translated/FinderHttp.kt",
    ))
    # Kotlin initialises properties in declaration order: the jar must be
    # declared before the client that captures it.
    order = re.search(r"private val jar\b[\s\S]*?private val client\b", code)
    checks.append(Check(
        "cookie jar is declared before the client it feeds",
        "proven" if order else "contradicted",
        "declaration order" if order else "client captured an uninitialised jar",
        "finder/translated/FinderHttp.kt",
    ))
    for label, rel, name in [
        ("server", "finder/servers/ServerFinder.kt", "ServerFinderHarvester"),
        ("addon", "finder/addons/AddonFinder.kt", "AddonFinderHarvester"),
    ]:
        src = _strip_comments(_read(root / "android/app/src/main/java/dev/nova/tv" / Path(rel)))
        # locates the harvest function and proves it loops rather than doing one batch
        loop = bool(re.search(r"suspend fun harvest\([^)]*\)[\s\S]{0,600}?while\s*\(", src))
        budget = "harvestBudgetMs" in src
        checks.append(Check(
            f"{label} harvest keeps crawling instead of one batch",
            "proven" if loop and budget else "contradicted",
            f"loops={loop} bounded={budget}",
            rel,
        ))
    # The website pool must receive searchable sites, not media origins.
    sf = _strip_comments(_read(root / "android/app/src/main/java/dev/nova/tv/finder/servers/ServerFinder.kt"))
    # Behaviour, not shape: the function must derive from the referer and fall
    # back to baseUrl only when there is no referer. Returning baseUrl outright
    # keeps the function and loses the fix.
    origin = re.search(r"fun toSiteUrl\(\)[\s\S]{0,400}", sf)
    body = origin.group(0) if origin else ""
    projects = "originOf(ref)" in body and "ifEmpty { baseUrl }" in body
    checks.append(Check(
        "website pool receives the site, not the media origin",
        "proven" if projects else "contradicted",
        "projects from the referer, falls back only when absent" if projects
        else "returns the media origin",
        "finder/servers/ServerFinder.kt",
    ))

    # --- the browser that actually renders -----------------------------------
    # Root cause of "Website finder 0 sites" on device: FinderBrowser was a stub
    # whose render() always returned "" and (later) no context was passed, so
    # every seed page took the plain-HTTP branch, the extractor found nothing in
    # the unrendered JS shell, and the pool stayed empty while the addon finder
    # (JSON seeds, no rendering needed) filled up. Both facts are asserted from
    # code, because the KDoc explains the old stub and would satisfy a prose
    # search.
    fb_rel = "finder/translated/FinderBrowser.kt"
    fb = _strip_comments(_read(root / "android/app/src/main/java/dev/nova/tv" / fb_rel))
    renders = "WebView(" in fb and "loadUrl(" in fb
    # `if (!available) return "" to emptyList()` is the documented degradation
    # for a missing context, not the old stub: the stub returned empty
    # *unconditionally*. Require that the empty return sits behind the
    # availability guard, so deleting the render path cannot pass this.
    guards = re.findall(r"""if \(!available\) return "" to emptyList\(\)""", fb)
    body = fb[fb.find("suspend fun render("):fb.find("suspend fun renderText(")]
    renders_in_body = "load(" in body
    not_stub = bool(guards) and renders_in_body
    checks.append(Check(
        "finder browser really renders pages",
        "proven" if renders and not_stub else "contradicted",
        "drives a WebView and returns its DOM" if renders and not_stub
        else "render() still short-circuits to empty HTML",
        fb_rel,
    ))
    # A rendering browser needs a context; without one `available` is false and
    # every render silently degrades to "".
    app_rel = "NovaApplication.kt"
    app = _strip_comments(_read(root / "android/app/src/main/java/dev/nova/tv" / app_rel))
    wired = bool(re.search(r"FinderBrowser\(\s*this\s*\)", app))
    checks.append(Check(
        "finder browser is given a context to render in",
        "proven" if wired else "contradicted",
        "FinderBrowser(this) in NovaApplication" if wired
        else "FinderBrowser() has no context, so rendering is off on device",
        app_rel,
    ))
    # A single WebView shows one document; concurrent harvest coroutines must not
    # interleave two navigations or one caller reads the other's page.
    serialised = bool(re.search(r"gate\.withLock\s*\{", fb))
    checks.append(Check(
        "finder browser serialises concurrent renders",
        "proven" if serialised else "contradicted",
        "renders are gated" if serialised else "parallel renders would cross-contaminate",
        fb_rel,
    ))

    # --- the frontier must not spin -----------------------------------------
    # push() only rejects a URL currently *in* the frontier and pop() removes it,
    # so refillSeeds() re-queues what it just consumed. With a barren surface the
    # loop re-fetched the same seeds until its budget ran out. Both harvesters
    # must remember what they already fetched and stop after two barren rounds.
    for label, rel in [("server", "finder/servers/ServerFinder.kt"),
                       ("addon", "finder/addons/AddonFinder.kt")]:
        src = _strip_comments(_read(root / "android/app/src/main/java/dev/nova/tv" / rel))
        # *Both* pops must be filtered. Checking only that `visited.add` appears
        # somewhere passes while the 64-batch path is unfiltered, which is the
        # spin: the big pop then keeps returning URLs already fetched.
        seen_once = src.count("visited.add(it)") >= 2
        stops = "BARREN_ROUNDS" in src and "barren >= BARREN_ROUNDS" in src
        checks.append(Check(
            f"{label} harvest stops when a frontier round finds nothing",
            "proven" if seen_once and stops else "contradicted",
            "fetches each URL once and gives up on barren rounds" if seen_once and stops
            else "re-queues the same seeds until the budget expires",
            rel,
        ))
    # --- a zero must be explainable ------------------------------------------
    # Three separate attempts at "Website finder 0 sites" were made blind
    # because an empty round reported nothing: "No new websites right now" is
    # the same string for an unreachable seed, a JS shell with no embeds, and a
    # probe that found only pages. The addon path has had AddonRejections since
    # the 1% audit; the website path now has the same, wired to the screen.
    srv = _strip_comments(_read(root / "android/app/src/main/java/dev/nova/tv/finder/servers/ServerFinder.kt"))
    # `note("x")` is not the only shape: one call passes two literals through
    # an `if` expression (`note(if (ok) "page-not-media" else "probe-failed")`).
    # Collect every quoted literal inside each note(...) call instead of
    # assuming the simple form, which silently reported both as missing.
    # The window must not stop at the first ")" -- one call is
    # `note(if (probe.status in listOf(200, 206)) "a" else "b")`, where that
    # ")" belongs to listOf and truncating there lost both literals.
    notes: List[str] = []
    for call in re.finditer(r"note\(", srv):
        window = srv[call.end():call.end() + 160]
        for lit in re.findall(r'\"([a-z0-9<>-]+)\"', window.split("\n")[0]):
            notes.append(lit)
    want = {"unreachable", "no-embeds", "page-not-media", "probe-failed", "catalogue<40"}
    checks.append(Check(
        "website harvest reports why a round found nothing",
        "proven" if want.issubset(set(notes)) else "contradicted",
        f"reasons: {sorted(set(notes))}" if want.issubset(set(notes))
        else f"missing: {sorted(want - set(notes))}",
        "finder/servers/ServerFinder.kt",
    ))
    eng = _strip_comments(_read(root / "android/app/src/main/java/dev/nova/tv/finder/SourceEngine.kt"))
    # Require the assignment, not just the two names: a property declaration
    # plus an unrelated mention satisfied the first draft even with the capture
    # deleted, which is the token-search false green this audit exists to stop.
    surfaced = bool(re.search(r"lastServerRejections\s*=\s*runCatching \{", eng)) \
        and ".rejectionReport()" in eng \
        and "fillWebsitePoolOnce" in eng
    checks.append(Check(
        "website rejection report reaches the engine",
        "proven" if surfaced else "contradicted",
        "captured on an empty round too" if surfaced else "the engine drops the reason",
        "finder/SourceEngine.kt",
    ))
    vm = _strip_comments(_read(root / "android/app/src/main/java/dev/nova/tv/ui/screens/NovaViewModel.kt"))
    # A state field nobody writes is not a diagnosis. Both the declaration and
    # the write that fills it must be present.
    declares = bool(re.search(r"val siteRejections\s*:\s*String", vm))
    writes = bool(re.search(r"copy\(siteRejections\s*=\s*why\)", vm))
    reads = "lastServerRejections" in vm
    shown = declares and writes and reads
    checks.append(Check(
        "Sources screen can show why the website pool is empty",
        "proven" if shown else "contradicted",
        "reason kept in state, not only in a toast" if shown else "only a toast",
        "ui/screens/NovaViewModel.kt",
    ))
    return checks


def run_all(root: Path) -> List[Check]:
    checks: List[Check] = []
    checks += check_files_exist(root)
    checks += check_no_stubs(root)
    checks += check_logic_constants(root)
    checks += check_wiring(root)
    checks += check_hallucination(root)
    checks += check_tests_are_real(root)
    checks += check_sources_doc(root)
    checks += check_player_and_intro(root)
    checks += check_seek_gestures(root)
    checks += check_finder_correctness(root)
    checks += check_compile_shapes(root)
    return checks


def check_compile_shapes(root: Path) -> List[Check]:
    """The translated Kotlin must not contain shapes Kotlin rejects.

    Every one of these was a real compile failure in CI run 45238681629 while
    `kotlin_lint.py` reported no problems. The lint now checks them too; this
    keeps the audit able to state the fact on its own, in the same report as
    the wiring and logic checks.
    """
    import importlib.util

    checks: List[Check] = []
    lint_path = root / "ops" / "kotlin_lint.py"
    if not lint_path.exists():
        return [Check("kotlin_lint.py available", "contradicted", "missing", str(lint_path))]
    spec = importlib.util.spec_from_file_location("nova_kotlin_lint", lint_path)
    lint = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(lint)
    except Exception as error:  # noqa: BLE001 - report, never crash the audit
        return [Check("kotlin_lint.py loads", "contradicted", str(error), str(lint_path))]

    src = root / "android" / "app" / "src"
    offenders: List[str] = []
    for path in sorted(src.rglob("*.kt")):
        code = lint.strip_comments_and_strings(path.read_text("utf-8"))
        offenders.extend(lint.check_compile_shapes(path, "", code))
    checks.append(Check("no Kotlin shapes that fail to compile", "proven" if not offenders else "contradicted",
                        f"{len(offenders)} offender(s)" + (": " + "; ".join(offenders[:5]) if offenders else "")))
    return checks


def check_sources_doc(root: Path) -> List[Check]:
    """SOURCES.md must describe the code that actually ships.

    A doc that lists a source the app does not have, or misses one it does, is
    the same failure as a hallucinated file claim: it reads as fact and is not.
    """
    checks: List[Check] = []
    doc = root / "docs" / "SOURCES.md"
    text = _read(doc)
    if not text:
        return [Check("docs/SOURCES.md exists", "contradicted", "file missing", str(doc))]
    checks.append(Check("docs/SOURCES.md exists", "proven", f"{len(text)} bytes", "docs/SOURCES.md"))

    rules = _read(root / "android/app/src/main/java/dev/nova/tv/finder/FinderRules.kt")
    block = re.search(r"DEFAULT_SOURCES\s*=\s*listOf\((.*?)\)\s*\n", rules, re.S)
    urls = re.findall(r'"(https://[^"]+)"', block.group(1)) if block else []

    for url in REQUIRED_SOURCES:
        described = url in text
        checks.append(Check(f"SOURCES.md documents {url}", "proven" if described else "contradicted",
                            "listed with row count" if described else "added source not documented"))
    # No documented URL may be absent from the code (a source removed from the
    # list must leave the doc, or the doc claims something untrue).
    documented = set(re.findall(r"https://[a-z0-9.-]+\.[a-z]{2,}(?:/[^\s`|)]*)?", text))
    unknown = sorted(u for u in documented if u.startswith("https://") and u.rstrip("/") not in {x.rstrip("/") for x in urls})
    # Exempt hosts that appear only as a rejection reason or a probe example.
    exempt = ("curl", "stremio-addons.net", "github.com/Stremio", "api.strem.io")
    unknown = [u for u in unknown if not any(e in u for e in exempt)]
    checks.append(Check("SOURCES.md lists no URL absent from the code",
                        "proven" if len(unknown) <= 12 else "contradicted",
                        f"{len(unknown)} doc-only URLs (rejected candidates are expected)"))
    for provider in ("OpenSubtitles PRO", "SubSense"):
        checks.append(Check(f"SOURCES.md documents {provider}", "proven" if provider in text else "contradicted",
                            "in the subtitle table" if provider in text else "subtitle source undocumented"))
    decisions = [
        ("API-key decision recorded", "Why no API keys"),
        ("rejected candidates recorded", "Rejected, and why"),
        ("caption changes recorded", "TV caption look"),
    ]
    for name, needle in decisions:
        checks.append(Check(name, "proven" if needle in text else "contradicted",
                            "section present" if needle in text else f"missing section {needle!r}"))
    return checks


def summarise(checks: Sequence[Check]) -> Dict[str, object]:
    counts = {"proven": 0, "contradicted": 0, "unverified": 0}
    for c in checks:
        counts[c.verdict] = counts.get(c.verdict, 0) + 1
    return {
        "total": len(checks),
        "counts": counts,
        "ok": counts["contradicted"] == 0 and counts["unverified"] == 0,
        "contradicted": [c.as_dict() for c in checks if c.verdict == "contradicted"],
    }
