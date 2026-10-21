# ZeroPlay TV UI refinement plan

## Scope

Refine the existing `tv/zeroplay-debrid` single-page ZeroPlay TV app and its Android TV shell without changing the title-resolution safety model or introducing arbitrary third-party services.

## Implementation approach

- Keep the existing single-file app and Vercel root at `tv/zeroplay-debrid`.
- Make TV mode a first-class interaction layer: visible, oversized focus rings; deterministic spatial navigation; contained horizontal rails; scroll-to-reveal for vertical settings and modal content; and stable focus restoration after rerenders.
- Move FastStream and M3U/download actions into a compact in-player action shelf rendered inside the player stage. The shelf is hidden during active playback and becomes visible on pause or when the viewer wakes player chrome with the remote.
- Preserve the existing FastStream EmbedAPI/native fallback behavior, exact-title server matching, download safeguards, and M3U validation. The new controls call those existing functions rather than duplicating playback logic.
- Round controls, toggles, selects, text inputs, and range inputs consistently and increase TV hit areas without changing desktop density more than necessary.
- Keep the Android TV wrapper’s existing remote/media-key bridge and update only if needed to make browser focus behavior reliable.

## Design

### Candidate styles

- **Cinematic control room** (0.06): deep graphite surfaces, violet focus energy, large rail cards, and controls that disappear into the picture while playing.
- **Living-room glass** (0.08): soft translucent panels, high-radius controls, and calm blue-violet focus states.
- **Console utility** (0.07): compact neon status blocks, hard-edged grids, and dense technical readouts.

Chosen: **Cinematic control room**.

- **Design Movement:** premium streaming-player UI adapted for a 10-foot TV experience.
- **Core Principles:** remote-first; content stays unobstructed during playback; every focus move is visible; settings are readable and bounded.
- **Color Philosophy:** near-black surfaces preserve cinematic contrast; violet is the ZeroPlay action color; bright cyan is reserved for focus and navigation feedback.
- **Layout Paradigm:** content rails and player stage lead the layout; controls dock to the edge of the actual player rather than floating above unrelated content.
- **Signature Elements:** violet focus halo, rounded action shelf inside the player stage, and rail progress indicators.
- **Interaction Philosophy:** Arrow keys move within the current rail or panel; OK activates; Back closes the current layer; Play/Pause wakes the player shelf.
- **Animation:** short, low-cost transitions; no blur or long motion on Android TV; chrome fades only while playback is confirmed active.
- **Typography System:** system sans with large, high-contrast headings and minimum TV body sizing around 1rem.
- **Brand Essence:** ZeroPlay makes remote-controlled discovery and playback feel immediate and dependable; cinematic, direct, resilient.
- **Brand Voice:** concise and action-oriented. Example lines: “Pause to open player tools.” “Choose a source, then press OK to play.”
- **Wordmark & Logo:** retain the existing play-mark badge and ZeroPlay wordmark.
- **Signature Brand Color:** ZeroPlay violet `#8a2be2`.

## Project structure

- `tv/zeroplay-debrid/index.html`: app markup, CSS, routes, player, downloads, M3U extractor, and TV focus engine.
- `tv/zeroplay-debrid/tests/*.test.cjs`: static and extracted-function regression coverage.
- `tv/android-tv/app/src/main/java/.../MainActivity.java`: Android TV WebView shell and remote/media-key bridge.
- `tv/zeroplay-debrid/vercel.json`: Vercel build, rewrites, and security headers.

## Required behavior

- FastStream and M3U/download actions are inside the player stage and do not block the media surface while playing.
- Those actions appear on pause or when player chrome is explicitly awakened, and disappear again during confirmed playback.
- D-pad arrows and OK work for cards, rails, menus, toggles, selects, inputs, player controls, download controls, and modal content.
- Horizontal rail arrows stay in the active rail and vertical focus scrolls the nearest scroll container into view.
- TV controls, toggles, selects, and inputs are visibly rounded and have oversized focus states.
- Downloads and M3U extraction remain subject to the existing exact-title, direct-media, size, CORS, and playlist safety checks.
