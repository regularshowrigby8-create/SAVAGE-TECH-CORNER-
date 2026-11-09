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
3. **Details screen** — ARVIO-style section focus (actions → seasons → episodes
   → cast → similar) on top of the existing modal, keeping every current action.
4. **Search** — one screen with a persistent query box and result rows that
   reuse the shelf renderer.
5. **Library / Watchlist / Downloads** — one shelf language, real empty states,
   wired actions only.
6. **Settings / Sources** — dashboard density is correct here; align it to the
   same tokens and remove the remaining duplicate CSS layers.
