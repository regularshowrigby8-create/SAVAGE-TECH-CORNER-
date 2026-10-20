# ZeroPlay website

**Current website version: ZeroPlay 2.2 Web.**

Responsive movie/TV website using the same core discovery experience as ZeroPlay. The website intentionally does **not** include Live TV, PPV / Sports, or Live Sports.

## ZeroPlay 2.2 additions

- Embedwave (BETA) source selection alongside VidStuck.
- Local profiles, person discovery, Hidden Gems, Because You Watched and episode-calendar helpers.
- Tab/pagination fixes that prevent runaway or unrelated catalog appends.
- Improved in-place page appends and cache/bundle refresh handling.

## Website-only experience upgrades

The web build now includes:

- Continuous movie and TV-series loading with automatic retry, deduplication and end-of-results handling.
- Movie/series browsing filters for genre, year, rating and sort order.
- Search suggestions, trending search shortcuts, and All / Movies / TV Series result filtering.
- Shareable browser routes such as `/movie/299534`, `/tv/1399`, `/movies`, `/series` and `/search?q=...`, with browser Back/Forward support and scroll restoration.
- Richer movie/series details including runtime, release date, status, countries, languages, rating/vote count, season/episode counts and TMDB links.
- Movie franchise/collection rows when TMDB reports that a movie belongs to a collection.
- Personalized homepage rows including Continue Watching, Watchlist, Because You Watched, Anime, recommendations, trending, popular titles and now playing.
- Homepage section toggles in web Settings.
- Desktop hover previews with Watch, Details and Watchlist actions.
- Keyboard navigation: `/` focuses search, arrow keys move between cards, and `W` toggles the focused title in the watchlist.
- Installable PWA support using the existing ZeroPlay icon and an offline-capable cached app shell.
- Web performance improvements including lazy image tuning, `content-visibility`, bounded enhancement caching and smarter next-page preloading.

TMDB powers discovery, provider browsing, metadata, cast, trailers and related titles. VidStuck and Embedwave (BETA) are the website playback sources. Libraries, Plan to Watch, collections and watch/search history stay local to the browser.

## Deploy to Vercel

1. Import `Zer0Spce/ZeroPlay` into Vercel from GitHub and use branch `main`.
2. Keep the project root at the repository root. The checked-in `vercel.json` runs `node web/scripts/build.cjs` and publishes `web/public`.
3. Add the environment variable `TMDB_API_KEY` in Vercel Project Settings → Environment Variables. Enable it for Production and Preview if you want preview deployments to load the real catalog.
4. Deploy. The browser uses `/api/tmdb`, which is backed by the Vercel Function at `api/tmdb.js`.
5. The checked-in rewrites send ZeroPlay deep links back to `index.html`, so shared movie/series/search URLs can load directly.
6. Future commits to the connected production branch can redeploy automatically through the Vercel Git integration.

The Vercel function keeps the TMDB key server-side. It validates supported TMDB routes and bounded parameters before forwarding the request, so it is not an arbitrary URL proxy. Search responses use private caching; normal catalog responses use short shared caching.

## Netlify compatibility

Netlify remains supported. Root `netlify.toml` runs the same build command, publishes `web/public`, maps `/api/tmdb` to `web/functions/tmdb.js`, and includes the same SPA deep-link rewrites. Configure `TMDB_API_KEY` in Netlify environment variables before deploying.

## Website behavior and security notes

No authentication or cross-device account synchronization is included; libraries stay in this browser.

VidStuck is embedded using its documented TMDB URLs and options. Playback progress is accepted only from the active iframe and validated provider origin, matching content ID/type where exposed and validated timestamps. Embedded popups and top-level redirects are restricted by the website controls and hosting Content Security Policy.

A normal website cannot inspect or remove content inside VidStuck's cross-origin iframe. The Android/Windows QR scanner, control filtering, remote mouse and audio boost do not run on the website. Provider advertisements/verification and playback availability remain controlled by VidStuck. The website's own close/title controls hide after idle time; the provider's controls remain its own.

## Local checks

Run:

```bash
node --test web/test/*.test.cjs
node web/scripts/build.cjs
```

The build check also validates JavaScript syntax for the web runtime files and validates the PWA manifest.

For Vercel local development, use `vercel dev` with `TMDB_API_KEY` configured outside source control. For Netlify, use `netlify dev`.

Credits: TMDB metadata/artwork; JustWatch availability through TMDB; VidStuck and Embedwave playback. This product uses the TMDB API but is not endorsed or certified by TMDB.
