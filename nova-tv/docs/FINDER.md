# Nova source finder — what changed

This describes the source-discovery layer after the "aggressive finder" pass.
It applies to both flavours: everything lives in `src/main`, so the TV and
mobile builds share one finder.

## 0. Two finders, two pools

The finders are not interchangeable, and neither are their pools.

| Finder | Pool | What it finds | How it is used |
| --- | --- | --- | --- |
| **Website finder** (`FinderKind.ZEROPLAY`, label "Website finder") | `WebsitePool` (6000 sites) | ordinary streaming **websites** — the same kind "Add website" saves | searched by title like a saved website: result page → episode → embeds/`m3u8`/`mp4` |
| **Addon finder** (`FinderKind.STREMIO`, label "Addon finder") | `ServerPool` (6000 manifests) | Stremio **addons** (manifests) | asked for `/stream/{type}/{id}.json` |

The two pools are separate files (`website_pool.json`, `server_pool.json`) and
separate fill loops. Switching a finder off stops only its own pool: the other
keeps serving playback. The viewer's own hand-added websites are always
searched, whichever finder is on.

The website pool is filled by `finder/servers/ServerFinder.kt`, the Kotlin
translation of `servers.py` (server finder), which replaced the old
`WebsiteSpider` script:

- **Seeds** — the 12 anime front-ends in `ServerFinder.SEEDS` (HiAnime,
  9anime, AniWatch, Zoro and their live mirrors), expanded into
  www/m/api mirrors first;
- **Extractor** — iframe/embed/source, inline-JS `file:`/`src:` and
  `.m3u8`/`.mpd` URLs, plus base64 blobs and `"sources"` JSON, pulled out of
  each page (`ServerExtractor`);
- **Probe** — byte-level buffer and poison probe (`ServerProber`): a bare
  200 is never trusted, HLS playlists are deepened one level and hexagated,
  buffer score is `100 - ttfb/40 - stalls*12 - jitter/25 + min(kbps/60, 35)`;
- **Catalogue** — sitemap first, then API, then pagination, clamped at
  250 000; a server needs a catalogue of at least 40 to survive.

Each validated server is stored in `servers_pool.json`
(`{schema, generated_at, cap, count, servers[]}`, cap 6000, ranked by
`scoreOf`: 55% catalogue, 30% quality tier, 15% buffer, minus latency) and
also offered to `WebsitePool` as a `SavedWebsite`. A checker re-probes the
weakest slice every 300 s, quarantines dead hosts to
`dead_servers.json/.txt`, and refills to the cap.

The addon pool is filled by `finder/addons/AddonFinder.kt`, the Kotlin
translation of `addons.py` (add-on finder), which replaced the old
`ServerHarvester` script, and by `StremioFinder`'s discovery, exactly as
described below. Addon discovery is GitHub (8 pages, raw manifest URLs),
npm (npms.io + registry.npmjs.org), Reddit (`r/StremioAddons` JSON) and the
open web (`stremio-addons.net/.com` and every link found there). Every
candidate must expose a Stremio manifest (`id, version, resources, types,
catalogs`), must answer real `/stream/{movie|series|anime}/{id}.json`
requests for `tt0111161` / `tt0944947` / `kitsu:1`, and is rejected outright
if any of those streams carry `infoHash`, a `magnet:` URL or a
torrentio-style name. Validated addons are stored in `addons_pool.json`
(cap 6000, ranked by `scoreOf`: 45% catalogue, 25% quality, 15% stream
breadth, 15% buffer, minus latency) and offered to `ServerPool` as
`FinderCandidate`s. The checker works the same way, quarantining to
`addons_dead.json/.txt`.

Both translated finders share `finder/translated/`: `FinderHelpers`
(host/origin/norm/provider/quality/mirrors/score, pure and unit-tested),
`FinderFingerprint` (rotating UA/language/timezone/XFF/client-hints, with
the document header set from servers.py and the CORS header set from
addons.py), `FinderHttp` (per-host fairness, 60 s circuit breaker,
403/429/503 ladder honouring Retry-After, GitHub token rotation) and
`FinderBrowser` (Playwright has no Android equivalent, so it always takes
the plain-HTTP branch the Python scripts take when Playwright is missing).
Transport is OkHttp + coroutines, storage is `PoolStore` JSON files instead
of SQLite, and every URL still passes `HttpClient.isAllowedUrl` on every
redirect hop.

## 0.1 Subtitles

`data/remote/SubtitleFinder.kt` adds two keyless engines next to the addon
subtitles already supported:

- **OpenSubtitles v3** (the Stremio endpoint) — language, release and encoding;
- **OpenSubtitles REST** (`rest.opensubtitles.org`) — IMDb/season/episode
  matched; its downloads are gzip `.srt` files, so they are decompressed into
  the device cache before the player sees them.

Each engine has its own switch on the Sources screen (`Subtitle sources`), and
the Subtitles panel offers `Refresh subtitles` for a title that had none at
Play time. Files are ordered by the preferred language (English by default),
then by downloads, and the panel labels each with its source and release.

## 1. Internet Archive removed

`FinderRules.REDUNDANT_NAMES` now carries `internet archive`,
`internetarchive`, `archive.org`, `archive org`, `archive-org`, `ia-archive`
and `archiveorg`, matched against the addon name **and** its host. There is also
an explicit `FinderRules.isInternetArchive()` used by the pool harvester.

Why it had to go: an archive catalogue answers for *every* id, so it produced a
"source" for any title that then played public-domain material instead. That is
the "I look for something, a source comes up, but it plays something completely
different" report.

## 2. Stremio sources removed from the ZeroPlay finder

`FinderRules.SOURCE_IDS` was `stremio-addons`, `catalog-graph`, `github`. The
first two are Stremio directories and `StremioFinder` already reads both, so
every host they yielded arrived twice. They are gone; the ZeroPlay finder is now
`web` + `github`.

`AddonFinder` (addons.py) does not read those directories at all: its
frontier is GitHub, npm, Reddit and the open web, so there is nothing to
duplicate with `StremioFinder`.

The Stremio finder now also reads `api.strem.io/addonscollection.json`, the
collection Stremio's own web app loads. It is a separate index from
stremio-addons.net, so it reaches hosts neither the directories nor the
`addon_catalog` graph list.

## 3. The script sequence replaced by an open-web crawler

New file: `finder/WebCrawler.kt`.

Each round does three things concurrently, 24 requests in flight:

1. **Seeds** — GitHub repository search across seven queries, public
   `awesome-stremio` lists, addon index pages, and DuckDuckGo HTML result pages
   for ten rotating queries.
2. **Link following** — every seed page is parsed for outbound links; the
   addon-looking ones are fetched too (depth 1, up to 80), so a list that only
   links to other lists still yields servers.
3. **Manifest probing** — every `manifest.json` link is fetched, and every
   addon-looking base URL is probed at `base/manifest.json`. A server survives
   only if its manifest parses as a stream addon for film or series
   (`FinderRules.parseManifest`).

Every stage has its own share of the caller's time budget, so a crawl started
inside a 30 s discovery window returns what it found instead of being cancelled
with nothing.

## 4. The 6000-slot pool now fills until it is full

`SourceEngine.syncPoolFill` used to stop after four empty rounds. It now loops
until `ServerPool.CAPACITY` (6000) is reached:

- a round that adds servers resets the counter and pauses 400 ms;
- an empty round backs off exponentially up to 60 s;
- every eighth empty round the retirement list is cleared, so hosts that came
  back online are reachable again.

`SourceEngine.PARALLELISM` went from 12 to 32, and the harvester's GitHub
sweep, page count and manifest budget grew to match the larger pool.

## 5. Dead and faulty servers are discarded automatically

- `ServerPool.noteResult` now evicts a server the moment it has failed
  `RETIRE_AFTER_MISSES` (2) times with zero successes, and remembers it in the
  retirement list so discovery cannot re-add it.
- `ServerPool.pruneDead()` does the same sweep in bulk and is called by the fill
  loop every round, so the pool is self-cleaning while it grows.
- `ServerPool.clearRetired()` is the escape hatch the backoff uses.

## 6. False identification fixed

`FinderRules.streamMatchesTitle()` is applied to **every** stream before it
enters the source list (`SourceEngine.EngineState.add`), whichever finder
produced it.

The rule is asymmetric — it only rejects on positive evidence of a different
title, never on absence of evidence, because most real addon labels say nothing
but "1080p WEB-DL 2.1 GB":

- the label names a season/episode and it is not the one requested → reject;
- the label prints a year more than one away from the title's → reject;
- the label carries two or more identity words and none of them appears in the
  title → reject (it names a different film);
- otherwise keep.

## 7. Twelve catalogue addons instead of one

`FinderRules.DEFAULT_SOURCES` now ships thirteen addons (twelve catalogues plus
OpenSubtitles). Every URL was checked live: manifest 200, a `catalog` resource,
and at least one non-empty catalogue row.

| Addon | Host |
|---|---|
| Cinemeta | `v3-cinemeta.strem.io` |
| The Movie Database Addon | `94c8cb9f702d-tmdb-addon.baby-beamup.club` |
| Streaming Catalogs | `7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club` |
| Streaming Catalogs Plus | `scplus.duckdns.org` |
| Anime Kitsu | `anime-kitsu.strem.fun` |
| Absolute Cinema | `cinema.debrid.in` |
| Horror | `horror.debrid.in` |
| Marvel | `addon-marvel.gonp.deno.net` |
| TvVoo | `tvvoo.stremio-italia.eu/cfg-it-uk-fr` |
| Sports Streams | `sports.highfly.dev` |
| YouTube | `v3-channels.strem.io` |
| NoTorrent | `addon.notorrent2.workers.dev` |
| Mico-Leão Dublado | `27a5b2bfe3c0-stremio-brazilian-addon.baby-beamup.club` |
| OpenSubtitles v3 (subtitles) | `opensubtitles-v3.strem.io` |

`CatalogRepository.installDefaults()` no longer returns early when addons are
already installed: built-ins are merged in on every launch, so a device set up
before this release actually receives the new catalogues. That early return is
why Cinemeta had ended up as the only connected catalogue.

## 8. Tests

- `FinderRulesTest`: wrong film rejected, right film kept, unlabelled label
  kept, wrong episode/season rejected, archive blocked, catalogue count,
  ZeroPlay source ids.
- `ServerPoolTest`: immediate eviction of a server that never answers, and
  `pruneDead` keeping servers that have answered.
- `ServerFinderTest`: cap/seeds, provider/quality/score formulas, extractor
  (iframe/JS/HLS, data-URL skip), probe kind/alive, store rank/weak/dead,
  frontier FIFO, `writePool` cap and `servers` key, mirrors.
- `AddonFinderTest`: cap/seeds, manifest paths, probe ids, torrent/magnet
  regexes, addon score/quality, store rank/dead, record-to-candidate
  conversion, `writePool` cap and `addons` key, queue blocklist.
- `:app:testTvDebugUnitTest` and `:app:testMobileDebugUnitTest` green
  (both flavours share `src/main`); `nova-tv/python` tests green.

<!-- retrigger -->
