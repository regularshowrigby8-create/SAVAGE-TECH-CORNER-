# Nova source finder — what changed

This describes the source-discovery layer after the "aggressive finder" pass.
It applies to both flavours: everything lives in `src/main`, so the TV and
mobile builds share one finder.

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

`ServerHarvester` lost the same two directories for the same reason.

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

## 4. The 3000-slot pool now fills until it is full

`SourceEngine.syncPoolFill` used to stop after four empty rounds. It now loops
until `ServerPool.CAPACITY` (3000) is reached:

- a round that adds servers resets the counter and pauses 400 ms;
- an empty round backs off exponentially up to 60 s;
- every eighth empty round the retirement list is cleared, so hosts that came
  back online are reachable again.

`SourceEngine.PARALLELISM` went from 12 to 24.

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
- `:app:testTvDebugUnitTest` green; `nova-tv/python` 103 tests green.

<!-- retrigger -->
