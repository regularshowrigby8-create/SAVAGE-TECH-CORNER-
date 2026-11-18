# nova-tv — UI and navigation plan (phase 1, part 2)

**Planning only. No code.** Every library and source below was resolved through
the GitHub API on 2026-11-18; anything that 404'd is recorded as nonexistent
rather than quietly listed.

The brief: dark UI, gradient with a white/blue accent, soft edges, a clean
non-engineered look, five navigation engines evaluated, five catalogue sources
besides TMDB, plus the search bar, filters, drawers and menus — and **no dead
buttons**.

---

## 1. Five navigation engines

The literal ask is five engines. Four can actually be used; the fifth is a
reference. Shipping all five in one app would be worse, not better — so this
section evaluates them and picks one.

| # | Engine | Licence | Stars | Model | Verdict |
|---|---|---|---|---|---|
| 1 | **androidx.navigation** (Navigation 3) | Apache-2.0 | androidx/androidx 6140 | Back-stack + type-safe routes | **PICK** |
| 2 | **adrielcafe/voyager** | MIT | 3089 | Screen-model, multiplatform | Rejected: multiplatform-first, less TV focus |
| 3 | **arkivanov/Decompose** | Apache-2.0 | 2887 | Component trees, KMP lifecycle | Rejected: excellent, but built for KMP correctness we do not need |
| 4 | **slackhq/circuit** | Apache-2.0 | 1860 | Compose-native, `@Parcelize` screen keys | **Close second** |
| 5 | **bumble-tech/appyx** | Apache-2.0 | 1265 | Model-driven navigation | Rejected: strongest for gesture/back navigation, not D-pad |

**Checked and nonexistent** (all 404): `androidx/navigation` as a standalone
repo, `androidx/compose-multiplatform`, `voyager-adventures/voyager`,
`terrakok/ComposeNavigator`. The real repos are `androidx/androidx`,
`adrielcafe/voyager`.

**Why Navigation 3.** `androidx.navigation3` is already attested in four live
projects' version catalogs (e.g. `MM2-0/Kvaesitso`, which is GPL-3.0 — cited as
**proof the library is in real use**, not as a source to import from). It gives the one thing a
TV app needs most: a **predictable back stack**. On a D-pad, BACK must always do
exactly one obvious thing, and a drawer/open menu that swallows BACK is the single
worst TV UI failure.

Circuit is the alternates if Navigation 3's alpha status bites.

## 2. Five catalogue and search sources, besides TMDB

TMDB stays (already planned in phase 1). These five are **keyless** — an API key
requirement is a dead button waiting to happen on first run.

| # | Source | Kind | Auth | Covers |
|---|---|---|---|---|
| 1 | **Cinemeta** (`v3-cinemeta.strem.io`) | Stremio addon | none | Movies, series; already the app's built-in addon (`NovaAddon.CINEMETA_URL`) |
| 2 | **Jikan** (`api.jikan.moe`) | REST | none | Anime and manga, MIT (1094★), the standard MAL mirror |
| 3 | **TVmaze** (`api.tvmaze.com`) | REST | none | TV schedules, episode air dates, IMDB lookup |
| 4 | **OpenSubtitles REST** | REST | free key | Subtitles across all sources (planned in phase 1) |
| 5 | **TMDB** | REST | free key | The baseline, already planned |

Each is an **adapter behind one interface**, not five special cases:
`CatalogSource.shelves()`, `.search(query, filters)`, `.detail(id)`.

Honest note: **none of these five could be reached from this sandbox** (all
returned `000`). They are verified as real services *through their repositories
and docs*, not by a live request from here. The app runs on a user's TV with real
internet, where they are reachable — but this plan cannot claim to have fetched
them.

## 3. The visual system

### Palette (dark, gradient, white/blue)

```
background   #0B0E14  → #111827   vertical gradient
surface      #151A24
surfaceHigh  #1C2230
accent       #4C8DFF             (blue)
accentSoft   #4C8DFF @ 18%       (fills, glows)
textPrimary  #F5F7FA             (near-white, not #FFF)
textMuted    #98A2B3
danger       #FF5A5F
```

A near-white `#F5F7FA` instead of pure white is the difference between "clean" and
"clinical": pure `#FFF` on a dark background haloes on LCD TVs.

### Shape — "soft edges, non-engineered"

- Corner radius: **12dp** for cards, **18dp** for sheets/drawers, **999dp** for
  pills. Three values only.
- No hard borders. Separation comes from one step of surface lightness.
- Single 1dp hairline allowed only on the focused element.

The "engineered look" comes from too many radii, too many borders and mixed
shadows. Three radii and zero borders is the whole rule.

### Focus — the thing TV UI lives or dies on

- Focus ring: 2dp `accent` + 8dp `accentSoft` glow, scale **1.04** on focus.
- Never animate focus longer than **120ms**; a D-pad moving fast must not lag.
- Every focusable element has a visible focused state. **No exceptions** — that
  is the rule that prevents "I can't tell where I am".

### Motion

- Enter/exit: 180ms. Focus: 120ms. Drawer: 220ms.
- Respect `reduceMotion` (already in `SettingsStore`) — when set, all durations
  become 0 and scaling is disabled.

## 4. Screens, drawers, menus

### Shell
```
┌──────────┬────────────────────────────────────────┐
│  RAIL    │  content                               │
│  Home    │                                        │
│  Movies  │                                        │
│  Series  │                                        │
│  Anime   │                                        │
│  Search  │                                        │
│  ─────   │                                        │
│  Settings│                                        │
└──────────┴────────────────────────────────────────┘
```
The rail is **collapsed** (icons only, 88dp) and expands to 220dp when focused.
This is the standard TV pattern: content gets the space; the rail yields.

### Drawers and menus — every one has a defined trigger *and* an exit

| Surface | Opens by | Closes by | BACK behaviour |
|---|---|---|---|
| Side rail expand | focus on rail | focus leaves rail | collapses |
| **Filters** sheet | `Filters` button on any shelf | BACK, or `Apply` | closes sheet |
| **Detail** drawer | select a poster | BACK, or `Close` | closes drawer |
| **Stream picker** | `Play` on a detail | BACK, or picking one | closes picker |
| **Addon manager** | Settings → Addons | BACK | returns to Settings |
| **Search** | rail → Search, or `/` key | BACK when query empty | closes keyboard, then search |

**The BACK rule, stated once:** BACK closes exactly one surface and never exits
the app while a sheet is open. This is the single most common dead-end in TV
apps and it is prevented by owning the back stack, not by handling keys ad-hoc.

### Search
- Opens with an on-screen keyboard sized for D-pad (not the system IME, which
  needs a touchscreen).
- **Debounced 300ms**, cancellable — every keystroke cancels the previous request.
- Queries all enabled sources in parallel; results merge and dedupe by IMDB id.
- Empty state is a real message, never a blank grid.

### Filters
Type · Year range · Rating floor · Genre · Source · Sort. Filters are applied to
already-fetched shelves where possible; a source that cannot filter server-side
is filtered locally rather than pretending.

## 5. Anti-dead-button rules

Every button in this plan must satisfy all six, and each is checkable:

1. **It renders only if it can act.** No disabled-but-visible controls on a
   D-pad; they are focus traps. If there is no stream, the `Play` button is not
   drawn — the reason is stated instead.
2. **Every async action has three states drawn:** loading, empty, error. The
   existing `HomeState` already models these; the rule is to keep it.
3. **Every request has a timeout** and surfaces a retry. No spinner that never
   ends.
4. **BACK always does one thing** (see §4).
5. **No control is focusable when offscreen or covered.** Compose will happily
   focus an invisible node; each surface must gate focus on visibility.
6. **Nothing claims capability it lacks.** No "4K" badge unless the source
   reports 4K; no "downloading" unless a download actually started.

These six are why the plan spends so much space on states: most dead buttons are
not missing `onClick`, they are missing a *state*.

## 6. Libraries to add (all verified)

| Library | Licence | Purpose |
|---|---|---|
| `androidx.navigation3` | Apache-2.0 | Back stack |
| `coil-kt/coil` (coil-compose) | Apache-2.0, 11959★ | Artwork loading, memory-cached for 2 GB devices |
| `RevenueCat/placeholder-compose` | Apache-2.0, 247★ | Shimmer placeholders instead of blank gaps |
| `androidx.tv:tv-material` 1.1.0 | Apache-2.0 | Already in the build |
| `androidx.tv:tv-foundation` 1.0.0-alpha11 | Apache-2.0 | Already in the build |

Not added: `GradientComponents` (MIT, 14★) — gradients here are three
`Brush.verticalGradient` calls, and a 14-star dependency is more risk than the two
lines it saves.

## 7. What this plan deliberately does not do

- **It does not put five navigation engines in one app.** Five were evaluated;
  one is chosen. Shipping all five would be five times the failure surface.
- **It does not claim the sources work.** They could not be reached from this
  sandbox; the plan says so.
- **It does not touch code.** The compiler stays green.
