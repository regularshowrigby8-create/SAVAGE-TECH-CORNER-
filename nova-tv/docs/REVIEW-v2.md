# nova-tv v2 — criticism and debug review

Scope: the Kotlin app under `nova-tv/android` on branch `nova-tv-v2`, plus the
Python scripts under `nova-tv/python` and `nova-tv/ops`.

## What v2 changes

- **Multi-threaded source engine** (`finder/SourceEngine.kt`). Installed
  addons, the ZeroPlay finder, the Stremio finder and the Title finder start at
  once on a bounded IO pool and stream a ranked `SourceSnapshot` as each answer
  arrives. Verification of discovered addons runs 8 at a time.
- **Three finders, each switchable on the Sources screen**
  - ZeroPlay finder: the Kotlin port of ZeroPlay's `server-finder/core.js`.
  - Stremio finder (`finder/StremioFinder.kt`): only Stremio addon sources —
    stremio-addons.net pages and addon catalogues, manifests fetched 8 at a time.
  - Title finder (`finder/website/`): a port of ZeroPlay's `api/extract.js`
    website features. It searches the websites the user adds by title (and
    season/episode), scores candidates with ZeroPlay's title rules and extracts
    direct video links.
- **No torrents, 5 GB cap.** `FinderRules` rejects `p2p` manifests, magnet /
  infoHash streams, and any stream whose declared or probed size is over
  5 GiB. `StreamRanker` re-checks the probed size before a stream is offered.
- **Anti-buffering**
  - `StreamProbe` probes the top candidates in parallel (6 at a time), drops
    dead hosts, records throughput in `HostHealth`, and warms the connection.
  - The player is seeded with the measured bandwidth, so the first segment is
    fetched at a sensible bitrate.
  - Ranking prefers files that fit the measured throughput over the biggest file.
  - A stall watchdog in `NovaPlayer` moves to the next source after 3 rebuffers
    in a minute, keeping the position.
  - Late sources are appended to the running player queue.
- **Preload button (FastStream mechanics)** — `player/preload/`. One button in
  the player cycles Preload off → Preload → Multi-thread preload; the choice is
  saved. For direct files the preloader fills a 2 MiB-fragment window 5 minutes
  ahead of the playhead into a disk cache (a third of free space, 256 MiB–2 GiB)
  that ExoPlayer reads first. Multi-thread starts at one connection and adds one
  (up to six) only while the measured speed keeps rising by 5% or more. The
  progress bar shows the preloaded range. HLS / DASH are left to ExoPlayer's
  own segment buffering.
- **DNS** — `data/remote/NovaDns.kt`: Auto (system, then Cloudflare DoH, then
  Google DoH), or a fixed provider, with a 10-minute cache and IPv4 first. A
  larger connection pool (24) and retry on connection failure.
- **Dual Audio section** — a rail destination of international titles
  (production country outside the English-speaking world). For these titles
  the ranker boosts releases that carry English plus another language, and
  the player starts on the English track; the Audio panel switches tracks.
- **3000-server pool** — `finder/pool/`. Every finder's discoveries plus a
  harvester that pages stremio-addons.net, Stremio's collection, Cinemeta's
  addon catalogues and GitHub search. Deduped by host + path, at most 4 per
  host, capped at 3000, saved on disk. The Title finder asks the next 150
  untried pool servers (16 at a time). If nothing is found, the pool
  scrambles once: servers that failed twice and never answered are retired
  and never re-added, proven servers stay, and new ones rotate in. Sources
  has Scramble servers and Revert (the last 3 pools are kept).
- **D-pad** — held keys are gated at ARVIO's 80 ms / 112 ms so focus never
  outruns composition, and Right on the last loaded card of a row is held so
  focus does not jump to another row while the next page loads.
- **UI**: icon rail, spotlight home with endless rails, Movies / Series browse
  with genre chips and an endless grid, search with an on-screen keyboard,
  My List, Continue Watching, detail page with episodes and a source sheet,
  Up Next card with an 8 s countdown, shimmer placeholders, focus scale/glow,
  route and detail animations. Explanatory text was removed.
- **No setup**: every service used is keyless — Cinemeta, the OpenSubtitles v3
  addon, stremio-addons.net and the public GitHub API.

## Bugs found and fixed during the review

| Area | Problem | Fix |
|---|---|---|
| SourceEngine | `repo.add(...)` returns a `Result`; failures were silently treated as success | Result is checked; only successful installs are recorded |
| SourceEngine | `installed` list was mutated from several coroutines | Made private and guarded by the engine mutex (`noteInstalled`) |
| ViewModel | Final progress save was lost because `playback` was nulled before the save ran | The request is passed into `saveProgress` explicitly |
| Player | OK on the Up Next card was swallowed by the player key handler | Chrome is hidden and keys go to the card while it shows |
| Website finder | Search slugs dropped articles (`the-bear` became `bear`), so `{slug}` searches missed | New `searchSlug` keeps articles; title matching still ignores them |
| Website finder | A one-word title accepted longer titles that start with it ("The Bear" matched "Bear Grylls") | One-token prefix matches score 72, below the 80 accept line |
| Networking | Many addon streams are plain `http`, which Android 9+ blocks | Cleartext allowed in `network_security_config.xml` (private hosts are still refused by the URL guard) |
| Lint | Unused import in `NovaSources.kt` | Removed |

## Pre-build audit (wiring, buttons, logic, overlays, UI)

| Check | Problem found | Fix |
|---|---|---|
| Wiring | Server harvester asked stremio-addons.net for `sort_by=updated_at`, which the API rejects with an error, so that directory silently added nothing | Uses `sort_by=stars` (verified: 8 pages, ~702 addons) |
| Dead button | Built-in addon rows were focusable but did nothing | Rendered as plain "Built in" rows, not focusable |
| Dead button | Dual Audio "more shelves" was wired to `{}` | `loadMoreDualShelves()` pages in more shelves |
| False status | Preload button reverted to "off" on HLS or before playback, looking dead | New `waiting` / `unavailable` states; button shows "No preload" when the stream can't be preloaded |
| Logic | Preloader could wait forever on servers without byte ranges | Probes `Range: bytes=0-0`; anything but 206 with a known total = unavailable; 5 straight failures stop it |
| Logic | Server pool: scramble on an empty pool wrote a useless history entry; retire rule too eager | Empty scramble just fills; retire needs 2 misses and 0 hits; pool flushed to disk before the final probe |
| Logic | Local `pool` variable shadowed the engine's pool, so results were never recorded | Renamed; results now reach the pool |
| Logic | Finder hint toast was immediately overwritten | One message per outcome |
| Overlay | Detail source sheet and Add-website sheet leaked D-pad focus to the page behind | Both sheets trap focus; focus returns to the button that opened them |
| UI | Side rail needed 472 dp but had 468 dp | Items 46 dp, spacing 6 dp |
| UI | Player controls row overflowed the 864 dp width | Row scrolls horizontally with focus |

## What WuPlay, ARVIO and Stremio have that Nova doesn't, and why

| Feature | Who has it | Why Nova skips it |
|---|---|---|
| Accounts, cloud sync, profiles, Trakt/SIMKL | All three | Needs sign-up; conflicts with "no setup" |
| Debrid, Telegram, Plex/Emby/Jellyfin, IPTV | ARVIO | Needs user accounts or servers |
| Torrent streaming server | Stremio | Torrents are excluded by design |
| Cast and crew pages, TMDB collections | WuPlay, ARVIO | Needs a TMDB key; Cinemeta has only names |
| Catalog reorder / hide, web configurator | WuPlay | Setup UI; not built yet |
| Google TV "Watch Next" row | WuPlay | Needs the tvprovider library; not built yet |
| Auto frame-rate display switching | ARVIO | Not ported yet; Media3 still does seamless-only switching |
| Similar titles row | WuPlay | Not built yet |

WuPlay's GitHub repos contain only a README, changelog and screenshots (no
source); DebridStream is closed too. Only WuPlay's public addon SDK ideas
(direct HTTP only, `bingeGroup`) and ARVIO's Apache-2.0 code were used.

## Checks run

- `./gradlew :app:testTvDebugUnitTest :app:testMobileDebugUnitTest :app:lintTvDebug :app:lintMobileDebug :app:assembleRelease`
  (see BUILD-STATUS.md for the result).
- `python3 nova-tv/ops/kotlin_lint.py` — clean.
- Python unit tests — 103 OK.
- Kotlin unit tests — 81 run per flavor, 0 failed. Android lint — 0 errors
  (tv 70 warnings, mobile 74).
- TV and mobile release APKs built and signed locally (8.5 / 8.9 MB,
  `apksigner verify` passes on both).
- `scripts/check_buttons.py buttons` — 8 "unrendered-state" findings, all
  pre-existing (10 before the mobile build; its seek bar now renders
  `bufferedPositionMs`). An earlier version of this note said only
  `audioDelayMs` — that undercounted. None is a dead button: no control sets
  them; they are engine/settings fields (`preferredQuality`, `subtitleSize`,
  `playbackRate` x2, `isExternal`, `playbackSpeed`, `isMuted`, `audioDelayMs`)
  that are populated and not displayed.
- `--help` smoke test over all 62 scripts — 0 failed.

## Mobile build

Same data, finders (ZeroPlay, Stremio, Title), 3000-server pool with
scramble/revert, DNS and FastStream-style preload as the TV build — only the
UI differs (`src/mobile`). Bottom tabs (Home, Movies, Series, Search, More),
auto-advancing spotlight, endless shelves and grids, pull to refresh, detail
page with episodes and a sources sheet. Touch player: tap for controls,
double-tap ±10 s, swipe for brightness/volume, seek bar showing buffered +
preloaded range, preload/sources/audio/subtitles/speed/next controls,
landscape full screen, picture-in-picture (auto-enter on Android 12+).

## Known limits

- Not installed on a device or emulator from this environment; playback is
  proven only by unit tests and a clean release build.
- No downloads and no trailer previews.
- Websites for the Title finder are added by the user on the Sources screen;
  none are bundled.
- The Title finder relies on candidates cached from earlier searches for
  episode jumps.
- WuPlay and DebridStream are closed source (DebridStream also needs debrid
  accounts), so only WuPlay's public SDK ideas and ARVIO's open code were used.
- The pool holds 3000, but today's public directories list fewer unique
  stream servers than that; it fills as GitHub pages are read over time.
- Preload works on direct files only.
- The imported ARVIO engine files still emit Media3 deprecation warnings.
