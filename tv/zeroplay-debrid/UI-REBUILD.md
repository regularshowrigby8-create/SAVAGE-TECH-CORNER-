# ZeroPlay TV — interface rebuild

The home screen is being replaced, not patched. ZeroPlay's features (keyless
discovery, provider registry, FastStream/direct playback, Multi-Server finder,
M3U extraction, downloads, watchlist, progress, artwork fallbacks) stay wired
exactly as they are; the surface around them is rebuilt.

Work happens one section at a time. **Phase 1 is complete and verified.**

---

## What was actually wrong

Measured from the app before the rebuild (`tv/zeroplay-debrid/index.html`), not
guessed:

| Symptom | Cause in the code |
| --- | --- |
| D-pad "doesn't respond well" | `moveTvFocus` kept Left/Right inside a rail and, on failure, opened the drawer (`dir==="left"` branch). Down at a rail edge fell into the leftmost item of the next row. Up from the first row could do nothing. |
| D-pad presses landing nowhere | `isTvFocusable` accepted `opacity: 0` nodes, and the poster card's `.hover-actions` overlay is `opacity: 0` until hover. The remote could focus a control it could not see. |
| Back doing the wrong thing | `handleZeroPlayTvBack` checked the drawer only *after* the player/modal branches in one path and *before* in another; the drawer's open state was duplicated. |
| Icons too big | TV profile scaled **text** up everywhere. `.zp-rail-icon` inherited ~`1rem` from a larger root; the CSS defined it twice (lines ~887 and ~2427) with no shared scale token. |
| Everything too big | `body.android-tv .rail-track .poster-card{flex-basis:clamp(210px,15.5vw,264px)}` (→ 396 px tall artwork), `body.android-tv .rail-head h2{font-size:1.72rem}`, `body.android-tv .hero-actions .btn{min-height:56px;font-size:1.05rem}`, `body.tv-mode .zp-home-featured .hero-body h1{font-size:clamp(3.1rem,6vw,5.8rem)}`, and a 480–760 px hero. |
| Focus invisible | Four different focus styles: `0 0 0 4px` glow on tiles, `outline:2px` on buttons, `transform:scale()` only on cards, none on links. |

---

## What the reference apps do right

Read from the upstream sources (shallow clones, inspected directly):

### Stremio — `Stremio/stremio-web` (JavaScript)

- `src/services/GamepadNavigation/useContentGamepadNavigation.tsx`
  - Spatial scoring: `distance = primary + secondary * 3`, direction filtered by
    centre comparison. Same-row neighbours always beat diagonals.
  - `getActiveScope()`: **one scope owns the D-pad**, in priority order
    `[data-gamepad-modal]` → open `.modals-container` → open dropdown → content.
  - On select it clicks the focused element, then re-checks the scope and
    **returns focus to the control that opened the overlay** (`lastFocused`).
- `src/components/MetaItem/styles.less` — poster is `8rem`; focus only shifts
  `scale: 0.95 → 1` with a 100 ms ease. No glow, no lift.
- `src/components/MetaRow/styles.less` — horizontal rail with scroll snapping,
  edge fade and `overscroll-behavior-x: contain`.

### NuvioTV — `NuvioMedia/NuvioTV` (Kotlin, Compose for TV)

- `ui/theme/ComponentTokens.kt` — the whole geometry budget in one object:
  poster **126×189**, backdrop **320×180**, continue-watching **260×146**,
  episode 320×207, row padding 48 dp, sidebar item 52 dp with a **22 dp icon**,
  `focusedBorderWidth = 2.dp`, `focusedScale = 1.02f`, icon sizes 14–36 dp.
- `ui/components/ContentCard.kt` — `CardDefaults.border(focusedBorder=…)` plus
  `CardDefaults.scale(focusedScale=…)`; the card tracks live focus so the title
  marquee starts only for the focused card.
- `ui/components/SidebarNavigation.kt` — the sidebar **expands on focus**
  (`onFocusChange` → `onExpandedChange`) and collapses when focus leaves.
- `ui/components/CatalogRowSection.kt` — `scrollToItem(focusedItemIndex)` on
  focus change, with a `blockingFocusExit` guard so a focus request is never
  fired at an item that is not composed yet.

### ARVIO — `ProdigyV21/ARVIO` (Kotlin, Compose for TV)

- `ui/focus/ArvioDpadFocus.kt` — `focusRestorer() + focusGroup()` per row, and
  an explicit note on why a restorer fallback is unsafe for lazy rows.
  **`ArvioDpadRepeatGate`**: holds a pressed D-pad key to 82 ms between repeats
  so a held direction does not fly through the list.
- `ui/focus/FocusVisibility.kt` — `focusRevealDelta(top, bottom, viewport)`: a
  pure function that decides how far to scroll to reveal the focused item.
- `ui/focus/TvFocusCoordinator.kt` — Details screens use **sections** with
  explicit left/right/up/down transition rules instead of raw spatial search.
- `ui/components/SettingsFocus.kt` — focus halo drawn as three cached strokes
  (white 0.035 / 0.07 / 0.9), no blur.
- `ui/components/Sidebar.kt` — 56 dp icon rail, active indicator bar + icon
  scale on focus.

### Streamflix / Streamable — `pwpp08/Streamable` (Kotlin, Views)

- `ui/motion/FocusScaleAnimator.kt` — spring `scale 1.0 → 1.08`, small lift,
  ring in `colorPrimary`; documented rule: *keep neighbours unscaled, scale
  only, no blur or shadow stacks, to stay cheap on 1 GB TV devices.*
- `ui/TvFocusSpring.kt` — 1.05 scale for controls that already draw their own
  focus fill.

### DebridStream — `brett0x0/debrid-stream` (Node/HTML)

- ZeroPlay's current palette and component language were ported from its
  `public/style.css` (stated in the app's own CSS header). That is why the
  settings/sources forms look right and the TV home does not: it is a
  **dashboard**, not a 10-foot shelf layout.

### WuPlay — `wuplayapp/wuplay-releases`

Closed source: releases and an issue tracker only, no application source to
read. Its public feature surface (profiles, `Who's Watching`, configurable
catalog rows, preferred languages, autoplay, stream picker in-player) is
already covered by ZeroPlay's existing features; nothing was copied from it.
Recorded here so the gap is explicit rather than invented.

---

## Phase 1 — navigation and focus contract ✅

Delivered in `index.html`:

- **One contract block** (`TV NAVIGATION CONTRACT`) replacing the old
  `moveTvFocus`: row membership (`tvRowOf`), reachable-only controls
  (`tvIsFocusable` now rejects `opacity: 0` and hidden ancestors), one scope
  order (`TV_FOCUS_SCOPES`: downloads → player → details → servers → drawer),
  reveal inside every scrollable ancestor, and a return-home stack.
- **Left/Right never leaves the row**; at the edge it stops. **Down at an edge
  lands on the closest card below** (not the leftmost). **Up from the first row
  reaches the header**; a dead press opens the menu instead of doing nothing.
- **Back unwinds the drawer first**, then overlay → player → details → page.
- **One focus ring** for cards, buttons, inputs, links
  (`--zp-focus-ring` + `--zp-focus`).
- **TV scale tokens** (`--zp-poster-w`, `--zp-landscape-w`, `--zp-continue-w`,
  `--zp-tile-w`, `--zp-icon-sm/md/lg`, `--zp-hero-min`, `--zp-hero-title`) with
  a TV profile of **132 px posters** (NuvioTV 126 dp, Stremio 8 rem), 300 px
  continue cards, 252 px category tiles, **20–24 px icons**, 400 px hero and a
  2 rem hero headline.
- **33 superseded rules deleted** (the 210–264 px posters, 1.72 rem rail
  headings, 5.8 rem TV hero headline, 58 px TV hero buttons, 72 px play
  circles, 365–470 px category tiles, and the two `min-width` overrides).
- **No dead buttons**: a focused poster no longer hides its own overlay. The
  card is a single D-pad stop that opens Details (where Play/Episodes/Servers
  already live), and the overlay's buttons are `tabindex="-1"` so keyboard and
  remote share one activation contract.

Verification: `npm test --prefix tv/zeroplay-debrid` → **303 checks across 8
suites**, including the new `tests/tv-navigation-contract.test.cjs` (33 checks)
which runs the real `moveTvFocus` against a 1920×1080 fake DOM: shelf
containment, edge-then-down, header round-trip, invisible-control rejection,
scope priority, focus recovery, and the TV size budget.

Two legacy assertions in `tests/android-tv-ui.test.cjs` were pinned to source
strings that Phase 1 deliberately replaced; they now assert the new mechanism
(`currentRow/nodeRow` row lock, `--zp-continue-w` token).

---

## Next sections

Each is a separate step and lands only when its own checks pass.

2. **Home structure** — hero as a single 16:9 billboard with a logo-or-title
   rule, then Continue Watching, then shelves built from the existing
   `railHTML`/`cardHTML` renderers (so artwork fallbacks and progress badges
   keep working). No new renderer duplicates.
3. **Details screen** — ARVIO-style section focus (`ArvioTvPlayer.kt`: actions → seasons → episodes
   → cast → similar) on top of the existing modal, keeping every current action.
4. **Search** — one screen with a persistent query box and result rows that
   reuse the shelf renderer.
5. **Library / Watchlist / Downloads** — one shelf language, real empty states,
   wired actions only.
6. **Settings / Sources** — dashboard density is correct here; align it to the
   same tokens and remove the remaining duplicate CSS layers.

---

## Server aggregation → one player (read from source, 2026-11-09)

Read from real checkouts, not reviews: NuvioTV (`StreamAutoPlaySelector.kt`,
`StreamAutoPlayPolicy.kt`, `StreamProviderGrouping.kt`, `Stream.kt`,
`StreamScreen.kt`, `StreamScreenViewModel.kt`) and ARVIO (`StreamRepository.kt`,
`PlayerSourceDiscovery.kt`, `SourceFailoverPolicy.kt`, `PlayerViewModel.kt`).

Five mechanisms do the work. None of them is a "source dropdown".

### 1. One normalised stream record for every server

Every backend — Stremio addon, plugin, scraper, debrid, direct file, external
player, IPTV — is flattened into the **same** record before the UI sees it.
Nuvio's `Stream` carries `url | infoHash/fileIdx | externalUrl | ytId`,
`behaviorHints.proxyHeaders`, `addonName`, `pluginSourceName`, `quality`,
`qualityValue`, `debridCacheStatus`, `badges`, `subtitles`, `serverTarget`.
ARVIO's `StreamSource` is the same shape.

**Consequence for ZeroPlay:** the UI never renders a provider. It renders a
stream that happens to remember where it came from. That is exactly why these
apps have no "select your source" step anywhere in the player.

### 2. Progressive merge, never a blocking spinner

ARVIO `mergePlayerSourceDiscovery` combines two flows — addon batches and plugin
batches — and emits a `ProgressiveStreamResult(streams, completedAddons,
totalAddons, isFinal, notices)`. Each side starts with an empty emit so neither
waits for the other; `channelFlow` + `withTimeoutOrNull(30s)` bounds the slow
side. Dedupe is by `providerScopedStreamIdentity` = `addonId|url|source`.
`notices` carries addon *error messages* so a failed server is reported instead
of vanishing.

Nuvio does the same per addon: `StreamScreenViewModel` fills results as they
arrive, and autoplay may fire **before** the list is final.

**Consequence:** the picker is a live list that grows, with an honest
`3 of 7 servers answered` counter — not `Loading…` then a finished list.

### 3. Ordering is policy, not insertion order

Nuvio `orderAddonStreams(streams, installedOrder, preferredNames)`:
1. preferred providers first,
2. then entries containing a **direct debrid** stream,
3. then installed addons in user order,
4. then plugins.

`isPlayable()` filters non-playable records (external links, un-cached torrents
still `CHECKING`) *before* selection. `selectAutoPlayStream` scopes by source
(ALL_SOURCES / INSTALLED_ADDONS_ONLY / ENABLED_PLUGINS_ONLY), applies
`StreamAutoPlayMode` (MANUAL / FIRST_STREAM / REGEX_MATCH), and
`StreamAutoPlayPolicy.isEffectivelyEnabled` is true if autoplay is on **or**
"reuse last link" is on.

### 4. Failover is in the player, not in the picker

ARVIO `canTryFailoverSource(candidate, playing, playingUrl, placeholders)`
compares `PlaybackRequestIdentity(url, headers)` so the same request is never
retried, and previously failed placeholders are skipped.
`playingRequestIndex` locates the current stream inside the list for "next
source" navigation. `safePlaybackHeaders()` sanitises proxy headers before use.

**Consequence:** a failing stream silently advances to the next candidate and
the player shows `Source 2 of 5` instead of an error box.

### 5. Grouping is a display concern only

Nuvio `groupStreamsByProvider` groups by `addonName`, then by
`pluginSourceName`, alphabetically, and renders a **header only when the
provider actually changes** (`streamProviderHeader` compares with `previous`).
So a unified list still reads as "these came from Torrentio" without becoming a
folder tree.

### What ZeroPlay already has, and what it lacks

Already present and worth keeping: canonical identity verification
(`buildTitleIdentity`, `identityScore`, `identityVerified/Rejected`), variant
normalisation (`sortVariantsForQuality`, `chooseVariantForQuality`), progressive
search (`findTitleServers({onUpdate})`), racing (`measureSpeed`), failover
(`directQueue`, `tried`), and honest "unrelated servers hidden" reporting.

Missing, and what this rebuild adds:
- a **single unified list** — today servers are split into three headings
  (`Verified direct` / `Verified website` / `Exact-ID players`), which is a
  provider taxonomy, the thing these apps deliberately avoid;
- **progressive counts** in the picker header (`n of m answered`);
- **autoplay** as policy (`settings.stream.autoplay`) rather than a button the
  viewer must press every episode;
- **failover surfacing** in the player (`Source 2 of 5`) while a queue drains;
- **provider chips** as a filter, not as sections.

---

## Phase 2 — what changed (2026-11-09)

| Directive | Status | Where |
|---|---|---|
| D-pad on every part of the app | done | geometry engine (`tvRowKey`/`tvRowBand`/`tvSameRow`/`tvNearestAbove`/`tvNearestBelow`), header re-entry from the first content row, `tvIsFocusable` now walks ancestors so faded chrome is never an arrow target |
| D-pad in Settings | done | sticky section strip (`data-panel-tab`), `activateSettingsGroup()`, collapsible `data-panel-group` sections, `:focus-within` switch rows, `tvActivateTarget()` for Enter/Space on selects and sliders, selects release the D-pad at the edge of their range |
| Remove the giant border over cards | done | five stacked halos deleted; one `--zp-focus-ring` (2px) plus depth. Desktop focus is a hairline inset |
| Refine, don't add | done | no new files; every change lands in `index.html` and its existing suites |
| Servers unified into one player | done | single `#serverList` with `data-stream-filter` chips and provider chips; three provider-taxonomy sections removed |
| Player UI scrapped and rebuilt | done | `playerShell()` rewritten: top bar (identity + exits), transport row (play, ∓10, seek, servers, more), paused shelf for the real jobs, one More sheet for quality/speed/volume/PiP/fullscreen/download/details; `[data-tv-row]` on each row; `directQueueStatus()` reports "Source 2 of 5" |

**Preserved on purpose:** every element id the playback engine binds
(`playerStage`, `playerFrame`, `seekRange`, `volumeRange`, `playerQuality`,
`playerRate`, `btnPlayPause`, `btnBack10`, `btnForward10`, `btnMute`, `btnPiP`,
`btnFullscreen`, `btnFocusEmbed`, `btnPlayerServers`, `btnPrevEp`, `btnNextEp`,
`btnPlayerDownload`, `btnPlayerDetails`, `btnClosePlayer`, `playerMeta`,
`playerEngine`, `fastBadge`).

**Removed:** the Android TV rule that set `display:none!important` on player
tools (`#btnPiP`, `#playerRate`, `.volume-wrap`, `#btnPlayerDownload`,
`#btnPlayerDetails`, `#btnFocusEmbed`) — those controls now live inside the More
sheet, which is better than deleting them from the TV build.

**Tests:** 327 checks green (`npm test`), 57 of them the TV navigation contract.

### Settings, honestly (the bug worth remembering)

Splitting the everyday hub into six real sections moved the *This TV* markup into
its own function, but `deviceText`, `deviceProfile`, `abiText` and `ramText`
stayed behind as locals of `settingsHubHTML()`. Rendering the settings route then
threw a `ReferenceError`, the render pipeline caught it, and the app silently
presented the **Sources** page instead. Production looked "wrong" while every
test passed, because no test read the page after render.

Two fixes, both permanent:

1. the values now live in the panel that uses them;
2. `tests/settings-sections.test.cjs` compares every extracted panel against the
   locals of the hub it came from and fails on any name it does not declare.

Live verification after the fix shows the six-section settings page with no
duplicated headings and no missing panel.

**Test total: 358 checks.** Sections shipped: navigation + scale (Phase 1),
D-pad everywhere + settings sections + unified server list + rebuilt player
shell (Phase 2). Still queued: the Home structure pass itself (Section 2), which
is now cheap because the navigation engine underneath it is finally reliable.

---

## The player is ours now

Every one of the six reference apps does the same thing: a source hands over a
URL, and the app plays it. Stremio, ARVIO and Nuvio never embed somebody else's
player page and then try to drive it from outside; they resolve the stream,
mount their own engine, and keep the controls, the position, the level and the
failover decisions in their own process (`PlayerEngine.kt`, `ArvioTvPlayer.kt`;
`StreamAutoPlaySelector.kt`). That is also what makes resume, quality
and server switching feel instant instead of cosmetic.

ZeroPlay did the opposite. `player:"faststream-only"` mounted an
`https://faststream.online/player/#<url>` iframe and then posted messages into it
to ask for play, pause, seek and quality. Everything a viewer could see was ours
and everything a viewer could *do* belonged to another site. When that site
changed, or the iframe refused an origin, the buttons were decoration.

The bridge is gone — `fsCommand`, `fsBridge`, `resetFastStreamBridge`,
`mountFastStreamPlayer`, `mountDirectVideo`, `nativeCanPlayDirect` and the hidden
preload iframe no longer exist anywhere in the file. In their place:

- `vendor/hls.min.js` and `vendor/dash.min.js`, same-origin copies, loaded on
  demand through `loadScriptOnce`;
- `streamKindOf(url, declared)` decides `hls` / `dash` / `file` / `unknown`
  from the URL, the declared container, or the provider's own `type`;
- `zpEngineMount` builds the `<video>` in our stage and hands the URL to the
  right library; `zpEngineSetLevel`, `zpEngineApplyRate` and `zpEngineSetVolume`
  act on the library's real instance;
- `bindNativeTransport` feeds our own UI, health scores and failover from the
  element's own events, plus a stall timer that does not trust the site;
- `zpEngineDestroy` tears everything down on close, so the next stream starts
  clean.

The framed fallback survives only for pages that genuinely never expose a file.
To keep it rare, `api/extract.js` grew `mode=resolve`: it follows a provider page,
and one hop further into that page's player frame, and returns direct media URLs
only. `resolvePageToStreams()` asks for that before ZeroPlay ever frames a page.

Two things came back with the engine that the bridge used to fake. Auto quality
now starts at the highest level and steps down exactly one rung after seven
seconds of real buffering on our own element — and never overrides a level the
viewer picked. And levels are addressed by array index, because hls.js level
objects carry no id; the old bridge code was matching a field that does not
exist, which would have made the quality menu silently do nothing.

Build and policy had to follow: `npm run build` now copies `vendor/` into `dist/`
(the engines would have 404'd under `script-src 'self'`), and the CSP gained
`worker-src 'self' blob:` for the transmuxing worker.

**Test total: 378 checks**, including resolve-mode parsing and real level-setting
against a fake engine instance.

---

## Translating ARVIO's player into HTML

ARVIO's player is 3,900 lines of Kotlin + `androidx.media3` (ExoPlayer 1.9) drawing
into a native Android surface through Compose `AndroidView`. It cannot be imported
into a WebView — so it was translated, function by function, onto the engine
ZeroPlay already mounts.

**What already matched.** Their remote contract and ours turned out to be the same
design: `MediaPlayPause` toggles, `MediaFastForward`/`MediaRewind` step ten
seconds, arrows wake the chrome and focus play, Back closes chrome first and the
player second, and every menu captures Back before the transport sees it. No
change was needed there; the new panel now takes keys *before* the transport, the
same ordering ARVIO uses (`TvPlayerPanels.kt` key handling before the transport).

**What was missing, and is now real:**

| ARVIO | ZeroPlay now |
| --- | --- |
| `PlayerEngine.state` with buffered position | `zpEngineBufferedSec()` over the element's own `buffered` ranges |
| `AudioTrackItem` list + `selectAudioTrack` | `zpEngineAudioTracks()` / `zpEngineSelectAudio()` over `audioTracks`, or the HLS renditions when the element exposes none |
| `SubtitleTrackItem` list, external + embedded | `zpEngineSubtitleTracks()` merges element cues with provider-declared sidecar files |
| Subtitle delay / size / vertical position | `setSubtitleStyle()` on real settings, applied by our own cue painter |
| `ResizeMode` AUTO/FIT/STRETCH/CROP | `zpEngineApplyResize()` cycling fit / zoom / stretch on `object-fit` |
| Volume and aspect indicators | one `playerIndicator()` used by volume, mute, aspect, skips and subtitle changes |
| Tabbed subtitle/audio sheet (`TvSubtitleMenu`) | `#playerTracksSheet`, same two tabs, D-pad first |

**The one deliberate divergence.** ARVIO draws subtitles with Compose (`TvPlayerPanels.kt`,
`TvSubtitleSettingsPanel`) and can move and delay them freely. A browser's `::cue` cannot be positioned and cues cannot be
shifted in time, so `::cue` alone would have made three of the panel's controls
decorative. Instead the selected track is kept alive in `hidden` mode (cues still
fire, nothing is drawn by the element) and ZeroPlay paints the text itself in
`#playerSubtitles`. That is what makes delay, size and position honest, and the
tests assert the painted text at fixed timestamps — including that a −2s delay
shows a cue two seconds earlier and nothing at the original moment.

Subtitles arrive from two real places: a Stremio addon's `subtitles[]` (filtered to
timed-text files, carried on the variant), and a provider page's own `<track>` or
config-blob paths, which `api/extract.js` now returns from `parseWatch` and from
`mode=resolve`. Sidecar files are fetched through the existing `mode=text` relay
and converted SRT → WebVTT in the browser, so a provider behind CORS still works.

**Test total: 416 checks.**

---

## Porting the logic, not the surface

Importing ARVIO's player was never possible and wrapping it was refused: their
player is Kotlin + media3 on a native surface, their repo contains no web player
at all (`netlify-arvio-tv-site` is marketing pages), and a WebView bridge is the
pattern this rebuild deleted. So the *logic* was copied into HTML, file by file.

**`PlayerCompletionEffect.kt` → `handlePlaybackEnded()`, and it fixed a real bug.**
ZeroPlay's old handler was two lines of condition and then nothing: a movie that
ended left the viewer on its final frame with no way forward except Back, and an
episode ended with autoplay off did exactly the same. ARVIO's rule is that every
finished on-demand video either offers its next episode or leaves the player.
Now:

- a finished movie (or autoplay-off episode edge case) pauses, says so, and
  closes the player instead of freezing;
- a finished episode opens the **Up Next** card;
- completion is claimed once per episode (`endedHandledFor`), because `ended`
  stays true after dismissal and visibility alone can't guard a reopen;
- source tests never advance the episode count.

**`NextEpisodeOverlay.kt` → `#playerNextSheet`.** Same shape: backdrop, show,
episode title, season/episode, runtime, a 10-second countdown bar, **Play now**
focused, **Cancel** stops. ARVIO's overlay (`NextEpisodeOverlay.kt`) captures the whole remote while it is
open; ours does too (`handlePlayerNextKeys` runs before the transport and before
Back), so nothing acts behind the card.

**`NextEpisodePromptGate.kt` → the claim-once rule.** One `endedHandledFor` per
episode key, exactly the gate their comment describes.

**`PlayerPauseMetadata.kt` → `playerPauseOverview()` / `playerPlayingLabel()`.**
A paused TV now shows the episode title and its synopsis (falling back to the
show), plus position — previously the player only ever knew "Season 2 · Episode 4".
This needed the episode record to survive leaving the detail screen, so
`episodeMetaCache` remembers it and fetches the season endpoint if a deep link
skipped it.

**`PlayerExitTransition.kt` → `closePlayer()`.** Pause, fade 180ms, then tear
down — with a guard so an exit started for one player cannot destroy a player
opened during the fade, and `reduceMotion` leaving immediately.

**Not ported, deliberately:** skiploop/recap intervals. ARVIO gets them from a
skip-intro repository that queries a provider; ZeroPlay has no such source wired,
and shipping the button without the data would be exactly the dead control this
project bans. Left for when a skip-intro provider is actually added.

**Test total: 438 checks.**

## Defect pass: what the sofa reported, and what changed

Five defects were confirmed by reading the code, then fixed. Each one has a
check in `tests/tv-navigation-contract.test.cjs` ("regressions" block) that was
run against the previous build to prove it fails there and passes now.

**D1 — giant white borders.** `--zp-focus` was `#ffffff` and
`--zp-elevation-focus` was a `rgba(255,255,255,.92)` ring, so every focused card
wore a 2px pure-white frame at 1080p. Both now use the brand accent
(`--zp-focus: var(--zp-accent)`), and cards take an accent ring plus a lift
instead of a white halo.

**D2 — D-pad only worked on Home.** Rows were inferred by comparing two cards'
centre lines with a tolerance. A shelf that mixes a 168px continue-card with
198px posters therefore looked like two rows, and Left/Right refused to move
between them. Rows are now the scroll containers themselves (`tvRowOf` /
`tvRows`), which is what a viewer perceives as a shelf. `tvIsChrome` marks the
topbar and sidebar as chrome: never a Left/Right or Down neighbour, only
reachable with Up off the top shelf, and the first press on an unfocused page
lands on content.

**D3 — Up/Down could not scroll a rail.** The old code skipped any vertical
press inside the same row. `moveTvFocus` now moves between shelves while keeping
the horizontal position, and at a shelf edge with cards still off screen it
scrolls the shelf (`scrollWidth`/`scrollLeft`, class-checked through
`TV_X_SCROLL_CLASSES`) instead of swallowing the press.

**D4 — third-party players were still embedded.** `mountEmbedPlayer`,
`embedReferrerPolicyForUrl`, the whole `engine==="embed"` mode, the "Provider
page" engine option, the "Video controls" focus bridge and the framed-player
Back branch are deleted. A resolver that can only find a page now throws a
truthful error, the stage shows "No direct stream from <server>" with real
actions, and auto-failover moves on. The only iframes left in the file are
YouTube **trailers** — a preview surface, not playback.

**D5 — the player bar never disappeared.** The hide timer's callback returned
without rescheduling whenever the film was paused or buffering, so a stall left
the bars up for the session. Idle time is now counted in watched milliseconds
(`playerIdleStart` / `playerIdleAccumulate`), and the timer's `tick()` always
ends by hiding the bar or re-arming itself; it stands down while a repair sheet
(`playerSheetVisible`) or the Up Next card is open.

A note on the harness: an earlier regex over `className` silently stopped
matching, and the fake DOM could not model a scrolled shelf at all. Both are
fixed — shelf membership is checked through `classList`, and fake cards inside a
`.rail-track` offset by the track's `scrollLeft`, so scrolling is observable.
That is why these checks reproduce the sofa instead of agreeing with the code.

## The browser shell (GitHub Actions)

`tests/browser/home-navigation.cjs` existed but was a dead tool: `playwright`
was never a dependency, no workflow ran it, and `npm run test:browser` could
only fail with `MODULE_NOT_FOUND`. It is now a real environment.

`.github/workflows/zeroplay-tv-browser.yml` runs three jobs:

1. **Node suites** — the nine contract suites (462 checks).
2. **Chromium** — `npx playwright install --with-deps chromium`, then the
   acceptance suite, repeated three times in one job so an intermittent
   failure shows up as a flake instead of being dismissed. Screenshots of the
   1920×1080 frame are uploaded as `zeroplay-tv-screenshots`.
3. **guard-the-guard** — checks out `82d0440` (before these fixes), drops its
   `index.html` in, and runs the same suite. It *must* fail; if it ever passes,
   the suite has stopped measuring the defect and the job fails loudly.

It is a separate workflow on purpose: Factory 2's debug workflow is
contractually a single job. `GATE 2b` in `.claude/scripts/gates.sh` fails if
the workflow, the npm scripts, the Playwright dependency or the suite ever
disappear again (the repo's NO_PADDING rule).

Failures publish themselves as run annotations (`::error` / `::notice`),
because the run log endpoint is not always reachable, and the check count is
published too — a suite that silently skipped everything would otherwise look
identical to a passing one.

### What the real browser found that the fake DOM could not

- **The white border was four layers, not a token.** Beyond `--zp-focus`,
  nine more focus rules painted white: a 4px `rgba(255,255,255,.92)` ring plus
  a white inset frame on cards and category tiles, 3px white outlines on
  buttons, sidebar links, menu links and textareas, and their low-ram twins.
  A stylesheet-wide scan now fails if any focus rule paints white again.
- **The D-pad could be taken back behind the menu.** `scheduleTvInitialFocus`
  armed an unconditional 1400ms retry that outlived the modal which had
  blocked it, and `closePlayerNow`/`restoreTvFocus` returned focus to the page
  after 30–40ms without checking for an open drawer. On a television that is
  the remote pointing at the page behind the menu. Both now go through
  `tvOverlayOwnsFocus()`.
