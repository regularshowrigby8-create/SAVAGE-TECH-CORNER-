# nova-tv catalogue and subtitle sources

This file exists because "which sources are in the app, and why" was only
answerable by reading `FinderRules.kt`. Every entry below was fetched, and
what it returned is recorded. Nothing here was added from memory.

Reproduce the checks yourself:

```bash
python3 nova-tv/python/scripts/qa_translated.py --live   # probes every URL
curl -s https://<host>/manifest.json | head -c 400        # one manifest
```

The offline half of that script (`--live` off) runs in CI on every push and
fails the build if a URL is listed without recorded evidence, if a source URL
carries a key or token, or if a subtitle provider is declared but not handled.

## Catalogue sources (`FinderRules.DEFAULT_SOURCES`)

24 entries: 13 that shipped before, 10 added here, and the subtitle index.
A source is kept only if its live manifest declared a `catalog` resource and
at least one catalogue row.

### Added in this change

| URL | Rows | Types | Also declares |
|---|---|---|---|
| `https://tmdb.elfhosted.com` | 12 | movie, series | catalog, meta |
| `https://1fe84bc728af-imdb-catalogs.baby-beamup.club` | 2 | movie, series | catalog |
| `https://1fe84bc728af-rpdb.baby-beamup.club` | 10 | movie, series, other | catalog |
| `https://stremio-film-festivals.elfhosted.com` | 38 | movie | catalog |
| `https://c73485b8a7a2-latamtv.baby-beamup.club` | 1 | tv | catalog, meta, stream |
| `https://5db836ec3ef8-thepiratebay-ctl.baby-beamup.club` | 5 | movie, series | catalog, stream, meta |
| `https://1fe84bc728af-catalog-builder.baby-beamup.club` | 2 | movie, series | catalog |
| `https://24dfd6fd4287-stremio-tmdb.baby-beamup.club` | 1 | series | catalog, meta |
| `https://ftv-stremio.surge.sh` | 1 | tv | catalog, meta, stream |
| `https://stremio.github.io/stremio-static-addon-example` | 1 | movie | catalog, meta, stream |

Why these ten, in one line each:

- **tmdb.elfhosted.com** — same 12 rows as the Beamup TMDB deployment on a
  different host. Two deployments of one catalogue is deliberate: Beamup
  outages are common and the home screen would otherwise lose Popular and
  Trending entirely.
- **IMDb Catalogs** — IMDb's own genre rows (Heist, Superhero, Time Travel,
  Vampire, Zombie …), which Cinemeta's 19-genre list does not carry.
- **RPDB Catalogs** — New / Popular / Best rows for film, series and "other".
- **Film Festivals** — Cannes, Venice, Berlinale, Locarno, Sundance, TIFF,
  Rotterdam, San Sebastián, Karlovy Vary, BFI London, IDFA, SXSW, FIPRESCI and
  the Oscars. Arthouse that nothing else here provides.
- **TV en latino** — a Spanish-language live TV catalogue; the existing live TV
  sources are Italian/UK English and sports.
- **ThePirateBay Catalog** — genre rows plus search over the TPB index.
- **Catalog Builder** — a second IMDb-derived movie/series catalogue, so one
  builder being rate-limited does not empty the row.
- **TMDB Alternatives** — alternate-order series rows, for shows whose
  broadcast order is not the story order.
- **FTV Channels** — fashion/lifestyle live channels, a different `tv` pool
  from sports and general TV.
- **Now.sh Example** — the reference Stremio static addon. One small row, and
  the cheapest possible canary: if this answers and nothing else does, the
  network is fine and the other hosts are down.

### Rejected, and why

| Candidate | Why it is not in the list |
|---|---|
| **AIOMetadata** (`aiometadata.elfhosted.com`) | Manifest returns `"catalogs":[]` unconfigured. Catalogues only exist at `/stremio/<uuid>/manifest.json`, i.e. per-user. |
| **MyTrakt Sync** (`mytrakt.elfhosted.com`) | `configurationRequired: true` and `"catalogs":[]`; needs a Trakt account. |
| **Plexio** (`plexio.elfhosted.com`) | `"catalogs":[]` and needs a Plex token. |
| **TMDB Discover+** (`tmdb-discover-plus.elfhosted.com`) | `"catalogs":[]`, `configurationRequired: true`. |
| **Watchly** (`watchly.elfhosted.com`) | `"catalogs":[]`; recommendations come from *your* Stremio library. |
| **AIOLists** (`aiolists.elfhosted.com`) | Retired by ElfHosted (documented on their own site). |
| **Public Domain Movies** (`publicdomainmovies.*`) | Host unreachable at every name tried (`v3-`, bare, `.elfhosted`). Not added on a guess. |
| **Streaming Catalogs (original Beamup ID)** | The one ID we could not confirm live; `scplus.duckdns.org` already covers the same per-service rows and *was* confirmed. |
| **Anime Catalogs** (`1fe84bc728af-stremio-anime-catalogs…`) | `"catalogs":[]`, `configurationRequired: true`. |
| **MediaFusion / Comet / AIOStreams** | `"catalogs":[]` — stream-only, so they add nothing to a catalogue list. |
| **Discussio, AIORatings, SubMaker** | Stream or subtitle resources only; no catalogues. |
| Anything keyed (MDBList, Trakt, RPDB posters, Fanart, TMDB API) | Would need a personal key inside the APK. See the next section. |

## Why no API keys are baked in

This was requested, and it is worth stating plainly what happens if it is done
literally, because it changes a security property of the app:

1. **A key in an APK is a published key.** APKs are zip files; `unzip -p
   app.apk classes.dex | strings` finds a string constant in seconds, and
   every user of the app shares one quota. For TMDB that is 50 req/s across
   the whole install base — the app would rate-limit *itself* out of service,
   and the key would be revoked once it was found.
2. **A keyed addon URL is worse than a key.** The configured URL *is* the
   credential (AIOMetadata's own docs say to treat it as private). Baking one
   in hands every downloader the same personal library and watch history.
3. **None of it is necessary.** Every source above answers anonymously. The
   "keys" that would have been needed belong to the *rejected* list, not to
   the sources that work.

So the ten additions carry no keys because they do not need any, and the
addons that would need one are documented above rather than shipped broken.
If a key must be supported later, the shape is a field on the Sources screen
stored in `SettingsStore` (the app already keeps per-provider switches there),
not a constant in `FinderRules`.

## Subtitle sources (`SubtitleFinder`)

Five engines, all keyless, all merged and ranked by language match then
download count:

| Provider | Host | What it adds |
|---|---|---|
| OpenSubtitles | `opensubtitles-v3.strem.io` | the Stremio-official index, already present |
| **OpenSubtitles PRO** | `opensubtitlesv3-pro.dexter21767.com` | same library with ad/spam entries filtered, on a second host |
| **SubSense** | `subsense.nepiraw.com` | an aggregator over ~10 providers (Subf2m, Podnapisi, AnimeTosho, Gestdown, BetaSeries, YIFY, TVsubtitles, SubSource, SubDL, OpenSubtitles) |
| OpenSubtitles REST | `rest.opensubtitles.org` | release-matched search by IMDb id, gzip downloads unpacked to cache |
| Addon subtitles | any installed addon declaring `subtitles` | whatever the viewer installed |

All three added/kept hosts returned a manifest with
`"resources":["subtitles"]` and no key. PRO and SubSense are marked
`configurationRequired` in their manifests, but the unconfigured endpoint
still answers: configuration selects preferred languages, and Nova chooses the
language itself (`SubtitleFinder.find(lang = ...)`), so the panel is not left
empty for a viewer who never opened the addon's own setup page.

## The TV caption look

`SubtitleStyle` was refined so captions read as broadcast subtitling rather
than a phone overlay:

| Setting | Before | Now | Why |
|---|---|---|---|
| Line width | edge to edge | 80% of picture, centred | ~90 characters per line was well past the ~42 subtitling guidance |
| Bottom lift default | 8% | 9%, clamped to a 5% floor | 3–8% sits inside real TV overscan |
| Typeface | system default | bold sans-serif | distance viewing |
| Cinema edge | none | thin black outline under the box | the box alone vanished over sky/snow |
| Size control | cycles one way | steps up and down, stops at the ends | overshooting meant cycling all five to get back |

The horizontal inset is taken from `displayMetrics`, not `view.width`: the
style is applied from a `LaunchedEffect` before the first measure, when
`view.width` is still 0 and a padding derived from it would be silently
dropped.
