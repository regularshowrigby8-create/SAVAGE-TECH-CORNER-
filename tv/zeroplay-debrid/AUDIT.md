# ZeroPlay audit — player replacement + full-project error sweep

Date: 2026-11-17 · Scope: `tv/zeroplay-debrid` (app shell, player, feeds,
serverless functions, tests)

The task had two halves: replace the player with a feed-driven one (ARVIO /
Stremio / WuPlay style) with no external players and no FastStream, and sweep
the whole app for potential errors.

---

## 1. Player replacement

### Before

- Three hard-coded embed hosts: `vidstuck.cc`, `zxcstream.cc`, `embedwave.cc`.
  `sourceURL()` built `/embed/{type}/{id}` (or `/player/…`) per host. Playback
  was an iframe of somebody else's web player.
- A "FastStream" preload/optimiser namespace threaded through settings, feature
  switches, the player shelf (`btnPlayerFastStream`), downloads and discovery.
- A `window.message` listener trusted `PLAYER_EVENT` progress reports from any
  of those three origins — telemetry from a player the app did not own.
- `embedwaveFailover`, `LAST_RESORT`, `isEmbedwaveOption()` special-cased one
  embed host as a permanent last resort and blocked it as a default.

### After

- `SOURCES`, `SOURCE_HOSTS`, `sourceURL()`, `LAST_RESORT`,
  `isEmbedwaveOption()`, the embed branches in `allSources`,
  `resolveSourceURL`, `sourceHost`, `sourceSupportsTitle` and the player shelf
  are all removed. FastStream naming is gone from code, settings keys, UI copy
  and feature switches (`fastStreamProbe` → `engineProbe`).
- New feed layer, one contract:

  | function | role |
  | --- | --- |
  | `normalizeFeedBase` | accepts a base URL, a manifest URL, a `stremio://` link, strips `/config`, `/configure`, `/stream/…` |
  | `fetchFeedManifest` | validates an addon manifest; reports whether it has a `stream` resource |
  | `feedCatalogueId` | builds the real protocol id — `tt…` or `tt…:S:E`, else `tmdb:` |
  | `fetchFeedStreams` | `GET {base}/stream/{type}/{id}.json` |
  | `feedEntryToVariant` | classifies every stream entry and refuses the ones a browser cannot honestly play |
  | `feedStreamsForTitle` | one feed, one title, with per-reason counters |
  | `collectFeedStreams` | every installed feed in parallel, deduped and ranked |
  | `installFeedFromUrl` / `removeFeed` | manifest-gated install, clean removal |
  | `feedStatusLine` | human summary of what was found and what was dropped |

- Player integration: `drawPlayer` and `resolveSourcePlayback` prefer the feed
  shelf, so the streams the player opens and the list the Servers sheet shows
  are the same objects. Failover walks the same ranked list.
- UI: a **Feeds** panel in Sources (install, per-feed Test / Enable / Remove),
  a first-run player screen that installs the first feed without leaving
  playback, and copy that states plainly that nothing is framed.
- The cross-origin listener no longer accepts telemetry. It only honours a
  one-shot "open this external URL" request, and only from an origin whose feed
  is actually installed.

---

## 2. Errors found and fixed

Severity: **S1** user-visible data loss or security, **S2** broken behaviour,
**S3** correctness/robustness.

| # | Sev | Finding | Fix |
| --- | --- | --- | --- |
| 1 | S1 | `syncInstalledAddonsFromProviders()` rebuilt `settings.addons` from crawled providers only, silently deleting hand-installed feeds on the next crawl tick (runs on ~12 call sites, including every discovery cycle). | Merge instead of rebuild; manual feeds are preserved and win on base collision. Pinned by `tests/boot-feed.test.cjs`. |
| 2 | S1 | `discovery.autoStart` defaulted to `true`; the crawler marked found stream addons `installed` and synced them into the feed list, so a first run silently chose sources for the viewer. | Default is `false`. Discovery builds candidates; it does not install. |
| 3 | S1 | SSRF gap in `api/extract.js` and its Netlify twin: `guardUrl` blocked `10/8`, `192.168/16`, `169.254/16`, `172.16/12`, `127/8` and `::1` but **allowed `100.64.0.0/10`** (CGNAT, which is what Tailscale hands out), as well as `198.18/15`, `198.51.100/24`, `203.0.113/24`, `fc00::/7`, `fe80::/10` and `ff00::/8`. It also checked the literal hostname only, so a public-looking name resolving to a private address passed. | New `isBlockedAddress()` covers the full IPv4/IPv6 special-purpose ranges including IPv4-mapped forms; new async `guardUrlChecked()` also resolves the host and rejects blocked answers. Applied to the handler entry, `profileSite`, post-redirect checks and the embed hop. Both Vercel and Netlify twins. |
| 4 | S2 | `fetchFeedStreams` used `encodeURIComponent(id)`, turning the protocol id `tt0944947:2:3` into `tt0944947%3A2%3A3`. Every mainstream addon 404s on that, so **every episode lookup failed** while movies worked. | Only `%3A` is restored to `:`; every other character stays escaped. Pinned by `regressions.test.cjs`. |
| 5 | S2 | `normalizeFeedBase` kept `/config` and `/configure` on an install URL, so `stremio://addon.example/config` resolved to `https://addon.example/config` and every request 404'd. | `/config`, `/configure` and `/settings` are stripped. |
| 6 | S2 | `sourceSupportsTitle` returned `{supported:true}` for any unknown `kind`, so a stale `builtin` entry from an older install still advertised itself as a playable route. | Only `addon`, `custom` and `site` can answer; anything else is refused with a reason. |
| 7 | S3 | Three module-scope caches grew without bound for the life of the session: `episodeMetaCache`, `tvmazeEpisodeArtworkCache`, `deletedDownloadIds`. A TV session touching hundreds of titles grows them forever, and the app is never reloaded on a TV. | `rememberBounded()` (LRU, 300/200 entries) and `rememberDeletedDownloadId()` (500 tombstones). |
| 8 | S3 | Stale copy: the Servers sheet still said "Embedwave always ranks last" and stamped a `LAST RESORT` chip on that source, and the advanced-playback help text and the "embed page must load within" field described framing a page. | Rewritten to describe feeds. |
| 9 | S3 | Two settings were read (`settings.stream.resizeMode`, `settings.stream.subtitleStyle`) that did not appear in the defaults object, so a first run read `undefined` before assignment. | Confirmed normalised two blocks below the defaults; no change needed (recorded here so the next audit does not re-flag it). |

### Verified clean (no change needed)

- **`eval` / `new Function` / `document.write`**: none.
- **`innerHTML` sinks**: 0 write raw variables into markup without `esc()`;
  feed-derived strings reach the DOM through `esc()` or `textContent`.
- **`target="_blank"`**: none without `noopener`.
- **Duplicate top-level declarations**: none in the app script.
- **Dangling identifiers after the rework**: `LAST_RESORT`, `SOURCE_HOSTS`,
  `SOURCES`, `sourceURL`, `normalizePlayerEvent`, `faststreamPreload`,
  `fastStreamApiProbe`, `embedwaveFailover`, `isEmbedwaveOption` all reach 0
  references.
- **Response size**: `api/extract.js` streams with a 1.5 MB cap and refuses
  oversized playlists in `mode=text` rather than truncating.
- **Timers**: all 5 `setInterval` sites have a clear path (player timers are
  pushed to `playerTimers` and cleared on exit; the discovery interval is
  owned by `discoveryRuntime.timer`).
- **Python and JS syntax**: every `.py` and every non-vendor `.js/.cjs`
  compiles.
- **Known-acceptable (not fixed)**: `preloadSlotEl`, `playerIdleAccumulate`,
  `zpEngineLevels`, `zpEngineBufferedSec`, `zpEngineApplyRate`,
  `zpEngineSetVolume`, `tvSameRow`, `tvEdgeTolerance` are currently
  unreferenced. They are engine/UI helpers kept for the next wiring pass, not
  dead branches of a removed feature. Flagged here rather than deleted.

---

## 3. Tests

`npm test` — 480 checks across 11 files, all passing.

Changed to match the new contract (they encoded the removed embed hosts):

- `regressions.test.cjs` — embed URL assertions replaced with feed assertions
  (install-link normalisation, catalogue id shape, not-web-ready / torrent /
  external refusal, colon-preserving stream URL).
- `tv-player.test.cjs` — the "embedwave ranks last" ordering test became
  "streams before routes, then by score".
- `downloads-m3u.test.cjs` — `embedwaveFailover` / `fastStreamProbe` switches
  replaced by `engineProbe`.
- `android-tv-ui.test.cjs` — `btnPlayerFastStream` → `btnPlayerQuality`.

Added:

- `tests/boot-feed.test.cjs` — boots `index.html` in jsdom, opens Sources,
  installs a feed through the real UI, asserts discovery adds nothing of its
  own, asserts a crawler sync does not delete the manual feed, and resolves a
  movie to a direct stream.

Note: Playwright's Chromium cannot be downloaded in this sandbox, so
`npm run test:browser` was not run. The jsdom boot test covers the same boot
path.

---

## 4. Operational notes

- The Vercel project `zeroplay-rd` has `gitProviderOptions.createDeployments =
  "disabled"` and `disableRepositoryDispatchEvents = true`, so pushing never
  triggers a build. Deployments must be created explicitly.
- The project has SSO protection on (`all_except_custom_domains`), so preview
  URLs require a Vercel login; only `zeroplay-rd.vercel.app` is public.
- A feed install URL is a viewer-supplied host. The feed layer only reads
  `/manifest.json` and `/stream/…`; it never proxies or frames those hosts.
