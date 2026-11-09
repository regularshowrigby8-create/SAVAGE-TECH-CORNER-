# Home and navigation redesign

Implemented from the supplied `plan.html`, with Batch 7 taking precedence over
conflicting instructions in earlier batches.

## Baseline and scope

The session branch started from `main`, which did not contain the TV app. The
web app and unchanged Android wrapper were brought in from the `tv` snapshot at
`1fc5b40568ba2f8aaa7bb0c6ba55cc8fefdc01fc` without switching branches. The
historical bundled Netlify ZIP was not imported: it would contain stale UI.
No serverless, playback, discovery, downloads, or native-wrapper logic was
redesigned. No deployment or Vercel setting was changed.

## Batch integration

1. **Foundation:** dark canvas, warm accent, typography/spacing tokens, restrained
   surfaces, white focus rings, reduced motion.
2. **Home hierarchy:** Continue Watching once before the hero when progress
   exists; Watchlist and all original category/catalogue loading targets follow.
   The corrected opening retains actual `media_type`, `poster_path`, `logo_url`,
   etc. fields and the app's existing skeleton renderer.
3. **Featured hero:** adapt presentation to original `.hero`, `.hero-meta`,
   `.hero-logo`, and `.hero-actions` markup. No alternative hero renderer or
   unused `.zp-hero*` classes. Play, Details, Servers, Watchlist, logo fallback,
   trailer setup and the entire original Home loading tail remain intact.
4. **Rails/cards:** larger artwork, restrained metadata, clear focus and progress.
   Poster progress positioning is scoped to posters, not the player's timeline.
5. **Navigation:** add the sidebar structure missing from this snapshot, reusing
   the **one existing** delegated `data-menu-route` handler. Existing drawer and
   search wiring remain. Active route and `aria-current` cover Settings and
   Downloads too. More options reuses the drawer with trigger-focus restoration.
6. **Android TV:** larger controls and rails, low-memory/performance treatment,
   restrained motion. No new detail-screen geometry changes.
7. **Corrections:** preserve the working Home renderer after its first
   `bindCards(view)`; remove the old focused-poster Play pseudo-element and
   Android TV rules hiding Servers/Watchlist; omit abandoned command bars,
   duplicate keyboard/route listeners, invalid class selectors and drawer state.
   One desktop geometry contract owns offsets; Android TV uses a 288px sidebar
   at >=980px, and narrow layouts have no sidebar offset. A pending focus-restore
   callback cannot steal focus from a newly opened menu or overlay, and a closed
   menu cannot receive its delayed opening focus.

## Verification

From the repository root:

```sh
npm ci
npm test --prefix tv/zeroplay-debrid
npm run build --prefix tv/zeroplay-debrid

# Install a Playwright browser if one is not already available.
npx playwright install chromium
npm run test:browser --prefix tv/zeroplay-debrid
```

A system Chromium can be used instead:

```sh
CHROMIUM_EXECUTABLE_PATH=/path/to/chromium \
  npm run test:browser --prefix tv/zeroplay-debrid
```

`CHROMIUM_LIBRARY_PATH` optionally supplies its library path.
`TV_SCREENSHOT_DIR` optionally saves viewport screenshots outside the source tree.

The seven regression suites pass **270 checks**, including **45 new** Home and
navigation checks. The real Chromium harness passes **144 browser checks**:

- No-progress and saved-progress states, plus repeated Home activation.
- All ten original loading targets appear exactly once; all eight catalogue
  rails load through the existing helper (VM regression + browser coverage).
- Featured artwork and loaded-logo display; original Play, Details and Servers
  UI opens; Watchlist removes/adds the title; actions receive the correct identity.
- Every sidebar/drawer route activates once, including remote Enter on Home.
- Encoded search submission, menu Back closure and return to both menu triggers.
- Poster Enter opens Details, with focus decoration but no false Play glyph.
- Non-overlap/overflow at 1920×1080, 1280×720, 980×720, 960×720 and mobile 390×844;
  desktop and Android TV profiles.
- No uncaught exceptions or console errors in these tested browser flows.

Browser tests use deterministic catalogue/artwork/network fixtures. They verify
application wiring and the existing player UI, **not availability or successful
streaming from live third-party providers**. Physical Xiaomi remote/device and
production-network checks remain deployment/device validation tasks.
