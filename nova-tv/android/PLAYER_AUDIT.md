# Nova player audit

What was hollow, what was fixed, and what is still declared but not wired.
Everything below was checked against the code in this branch, not assumed.

## Fixed

| Report | Cause | Fix |
| --- | --- | --- |
| Seeking does not work | The player chrome drew a `ProgressBar` fed by engine state. It was display-only: no focus, no keys, no seek call. | `ScrubBar` in `ui/player/PlayerSurface.kt`, backed by `player/imported/ScrubState.kt`. Focus it, LEFT/RIGHT nudges in 10 s steps with a live preview, OK or leaving the bar commits through `NovaPlayer.seekTo`, which clamps 1.5 s short of the end. |
| Subtitles only have an "Off" button | The panel listed `Off` plus caption style rows. There was no way to add a file, retime cues, or remove a track. | `PlayerPanel.SUBTITLES` now has: Off, **Add subtitle file…** (SAF `OpenDocument`, persisted URI permission, MIME list from `SubtitleFiles`), **Delay later / earlier** in 250 ms steps, **Reset delay**, caption style/size/box/height, every embedded track, and **Remove** per sidecar. Cue retiming runs on a 100 ms loop through `SubtitleOffsetController`. |
| Aspect / resize button did nothing | `ExoPlayerEngine.setResizeMode` only wrote to its own state flow. `PlayerView.resizeMode` was never set. | `LaunchedEffect(playerView, engine.resizeMode)` maps AUTO/FIT/STRETCH/CROP onto `AspectRatioFrameLayout` modes on both surfaces. |
| Speed reset every title | `SettingsStore.setPlaybackRate` existed but nothing called it, and nothing applied the saved value. | `NovaState.playbackRate` is seeded from settings, `NovaViewModel.setPlaybackRate` persists it, and `NovaPlayerHost` applies it after `load()`. |
| No previous-episode control | Only `playNext` existed. | `previousEpisode()` / `playPrevious()` plus a shared `jumpTo()`; Prev and Next pills on TV and mobile. |
| Download silently grabbed the top source | `downloadBest()` called `app.downloads.start()` on `streams.firstOrNull()`. | `downloadBest()` now opens a picker listing every downloadable source with its real quality and byte size; `downloadStream(index)` starts the chosen one. A single usable source still starts straight away. |
| Dual audio leaked into the normal catalogue | `resolve()` enabled dual ranking when `preferEnglishAudio` was on, and `Stream.label` appended "Dual audio" unconditionally. | Both are gated on `route == Route.DUAL_AUDIO`. `SourceSheet` badges take a `dualMode` flag on TV and mobile. |
| White on light selection, dark on dark | `MobileTheme` had `onPrimary = Color.White` on `#4C8DFF`; mobile chips passed a hard white label colour into a white-filled selected chip; TV `FilterChip` used accent text on an accent tint; `SecondaryButton` active state did the same. | `onPrimary` is now `NovaPalette.background`; chip labels use `Color.Unspecified` so the chip's own content colour wins; TV chips use `textPrimary` on a 24 % accent tint; `SecondaryButton` content is `textPrimary` at all times. |
| TV detail page compressed, episodes cut off | A fixed `Column` with `Spacer(Modifier.weight(1f))` above `Episodes`. On a short viewport the season chips and episode row shared one band and the bottom of the cards was unreachable. | The page is one vertical scroll axis (`verticalScroll` + `PivotScroll`), with a fixed 28 dp gap between the actions row and the episode block and bottom padding for the last card. |
| Home catalogue cards cut off | The spotlight was a flat 250 dp, leaving under one row of height on a 1080p TV, and the shelf list had no weight. | `BoxWithConstraints` sizes the spotlight to 30 % of the viewport (150–250 dp) and the shelf `LazyColumn` takes `weight(1f)` of what remains. |
| TV search errors | Three separate faults: results were passed to `LazyVerticalGrid` keyed on `id`, so blank or repeated ids from sloppy addons threw "Key was already used"; failures were swallowed by `getOrDefault(emptyList())` and shown as "No results"; and server search only covers addons that declare a `search` extra, so with none configured it could never return anything. | Keys are now `id#index` (or `row-index` when blank) on TV and mobile; `NovaState.searchError` carries the real message and the screen shows "Search failed: …"; when server search returns nothing the query falls back to matching every shelf page already in memory; results are de-duplicated. |

## Dead buttons and declared-but-unwired code

`python3 nova-tv/python/scripts/check_buttons.py buttons` reports 7
`unrendered-state` findings. After this branch:

| Symbol | Status |
| --- | --- |
| `SettingsStore.playbackRate` | **Wired.** Read at state init, written by `setPlaybackRate`, applied on player load. |
| `PlayerEngineState.isMuted` | Derived from `volume == 0f` in `ExoPlayerEngine.setVolume`. Nothing reads it, but it is not a control; harmless. |
| `PlayerEngineState.audioDelayMs` | **Removed.** It was a state field and an engine method with no UI and no audio processor behind it. Shipping a fake control was worse than not having one. |
| `GestureRouter.zone` | Imported helper. It is used by the router's own `onMove` logic; the audit flags it because no screen reads the enum. Not a button. |
| `ScrubState.dirty` | Read inside `ScrubState.commit()`/`cancel()`. Audit false positive. |
| `FragmentPreloader.targetAheadMs` | Read by the preloader's own plan loop. Audit false positive. |
| `NovaState.restoreFocusId` | Read by `NovaApp` through `LocalFocusRestore`; the audit only looks for direct reads. False positive. |

`PlayerState.kt` (`playbackRate`) is an unused legacy model. It is not
referenced anywhere; it can be deleted in a follow-up without behaviour change.

## "Add website"

Confirmed by reading the code path, not the label:

1. `NovaViewModel.saveWebsite()` takes the draft URL, calls
   `app.websiteFinder.profile(draft)` under a timeout, and stores the result in
   `library.addWebsite(site)`.
2. `WebsiteFinder.profile()` fetches the page with browser headers, reads its
   search template and derives the site's query URL shape.
3. `SourceEngine` iterates `library.websites.value` on every resolve and calls
   `websites.findStreams(site, query)` for the exact title and episode.
4. Those streams join the same ranked list as every other finder, so they show
   up in the Sources sheet and in the player's failover queue.

So yes: adding a website adds that site's servers as sources for matching
titles. It does not mirror the site; it resolves playable media URLs at request
time, and a site that blocks bots will fail to profile (the toast says so).

## Verification run

- `python3 -m unittest discover -s tests` — 103 tests, OK.
- `python3 scripts/check_buttons.py buttons` — 7 findings, all triaged above.
- `python3 scripts/qa_import_check.py` — 62 modules checked, 0 broken.
- `python3 scripts/qa_deps.py` — stdlib only.
- `python3 scripts/ops_doctor.py` — OK.
- `./gradlew :app:compileTvDebugKotlin :app:compileMobileDebugKotlin` — green.
