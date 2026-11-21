# Where the player behaviour comes from

Every mechanism in `lib/player-core.js` was read out of a real, running player
before it was written here. This file records the repository, the file, and the
specific rule lifted, so a future change can be checked against the original
rather than against memory.

Repos were fetched with `codeload.github.com` and read directly. Versions read
on 2026-11-17.

---

## video.js v10 — `videojs/video.js`

**`packages/core/src/dom/store/features/controls.ts`** — the whole chrome model.

| Rule | Where |
|---|---|
| `computeVisible()` = lock held ‖ userActive ‖ paused ‖ casting | `computeVisible()` |
| `requestControlsLock()` is refcounted; each release is idempotent | `requestControlsLock()` |
| Releasing the **last** lock calls `setActive()`, granting a full idle window after a menu closes | release closure |
| `IDLE_DELAY = 2000`, `TAP_THRESHOLD = 250`, `TOUCH_SETTLE_DELAY = 500`, `CAPTURE_RELEASE_DELAY = 100` | constants |
| Synthetic `mouseleave` after `touchend` (Android) must be ignored | `isRecentTouch()` guard |
| Safari 16's `mouseleave` after a control releases pointer capture must be ignored | `isRecentCaptureRelease()` guard |
| On touch, `pointermove` must **not** flip visibility mid-gesture — only keep the timer alive | `onPointerMove` |
| A tap on a control must reset the idle timer, never pause it | `onPointerUp` |

Adopted verbatim as `shouldShowChrome()`, `createChromeLocks()`,
`createIdleController()`, and the `TIMING` constant table.

**`packages/core/src/dom/presentation/fullscreen.ts`** — the ordered ladder:
`requestFullscreen()` → `webkitRequestFullscreen()` →
`video.webkitSetPresentationMode('fullscreen')` (iOS, where element fullscreen
does not exist) → `video.requestFullscreen()`. Adopted as
`createFullscreenController()`.

**`packages/core/src/core/ui/pip-button/core.ts`** — `isPictureInPicture ?
exit : request`. Extended here, because the rejection path matters more than the
toggle: `NotAllowedError` means "needs a gesture", not "broken".

---

## plyr — `sampotts/plyr`

**`src/js/listeners.js`** — the idle scheduler.

- `2000 ms` on pointer devices, `3000 ms` on touch.
- One timer: every activity event clears and re-arms it, so timers never stack.
- `show` is driven by `['touchstart','touchmove','mousemove']` only.

Adopted as the idle window in `createIdleController()` and its touch mode.

**`src/js/fullscreen.js`** — confirms the iOS `webkitEnterFullscreen()` path that
video.js reaches through presentation mode.

---

## indigo-player — `matvp91/indigo-player`

**`src/Instance.ts`** — `canAutoplay()` gates `play()`; `setError()` unloads the
controller before reporting, so a dead engine cannot sit over a black screen.

**`StateExtension`** — `waitingForUser = !canAutoplay()`: autoplay being blocked
is a **state the UI renders**, not an error.

Adopted as `createAutoplayRecovery()`: try unmuted → retry muted (allowed by
every browser) → report `needs-gesture` so the caller shows a real play button.
The muted success is reported to the caller, so the UI can say "playing muted"
instead of silently dropping the soundtrack.

---

## clappr — `clappr/clappr`, vidstack — `vidstack/player`

Both treat playback failure as **data**: the engine reports a reason and the
player walks a ranked fallback list instead of dying on the first failure. This
is the shape of `queryAddonSources()` diagnostics and of the refusal reasons in
`streamEntryToResult()` (`torrent-only`, `external-page`, `proxy-headers`, …).

---

## openplayerjs — `openplayerjs/openplayerjs`

Read for control-surface organisation: one transport row, secondary tools behind
a menu, and a scrubbing readout that settles rather than snapping back. Informs
`SEEK_SETTLE_MS` and the deck/shelf split of the player shell.

---

## What was deliberately **not** copied

- **No vendored code.** Nothing is pasted in; the rules are re-implemented
  against these notes and covered by `tests/player-core.test.cjs`.
- **No new dependencies.** `lib/player-core.js` is dependency-free CommonJS.
- **No external players or iframes.** The ladders above drive this app's own
  `<video>` element only.
