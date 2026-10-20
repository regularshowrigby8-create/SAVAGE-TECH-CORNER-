# 📺 TV — ZeroPlay × DebridStream prototype

This folder is the new home base for the TV project. It was created inside
`SAVAGE-TECH-CORNER-` because this Arena session is bound to that repo/branch —
the whole `tv/` folder can be copied into the standalone `tv` GitHub repo as-is.

| Path | What it is |
| --- | --- |
| `upstream-zeroplay/` | Fresh sparse clone of [Zer0Spce/ZeroPlay](https://github.com/Zer0Spce/ZeroPlay) (`web/` + `api/` + root files), **unmodified reference**. Full clone (Android/TV/Windows) when needed: `git clone https://github.com/Zer0Spce/ZeroPlay` |
| `zeroplay-debrid/` | **The prototype app** — ZeroPlay's discovery/playback in DebridStream's UI, plus the Sources/FastStream feature set. This is what is deployed. |

**Live prototype:** https://zeroplay-rd.vercel.app
(Vercel project `zeroplay-rd`, root directory `tv/zeroplay-debrid`.)

**Status:** web prototype for review. Next phase after approval: adapt for the
Xiaomi TV Box (3rd gen) — Android TV focus/D-pad navigation, larger type,
remote-friendly player — building on ZeroPlay's TV experience in
`upstream-zeroplay/` and the upstream Android TV source.

See `zeroplay-debrid/README.md` for deploy instructions (Netlify + Vercel),
the source-manager/FastStream feature list, and the honest limitations
(5 GB cap scope, discovery model, embed quality control).

## Phase 3 (keyless pictures, dynamic website lookup, addon discovery, embedded FastStream)

- **Bundled TMDB on launch (original ZeroPlay model) + keyless fallback**: original
  ZeroPlay's release builds inject a project TMDB key from a private CI/hosting
  secret, so users never paste a key. The web equivalent here is the server-side
  `TMDB_API_KEY` / `TMDB_ACCESS_TOKEN` environment variable behind `/api/tmdb`;
  once the deployer sets it, the header shows “Catalog: TMDB (bundled)” and a
  pasted key is only an optional per-device override. Until/unless that env key is
  configured, the catalogue falls back to Stremio's keyless Cinemeta addon (real
  Metahub artwork, TMDB ids preserved via `moviedb_id`), then the offline demo.
- **Paste a homepage, get a lookup source (default for every added website)**:
  Sources → paste a site such as the `anisuge.tv` example → "Add website lookup".
  Anisuge is not built in or permanent. The app does **not** import/pull the
  website's covers or titles and does not create Home rails from it. It stores
  only the domain/search configuration. When the user selects an existing
  catalogue title/episode, `api/extract.js` (Netlify twin included) checks that
  site's public search/results for the exact title, resolves the matching
  episode/watch page, and plays its embed/direct stream if found. Explicit URL
  templates remain an optional advanced source type. SSRF-guarded; no
  login/paywall/CAPTCHA/DRM bypass.
- **Zero-config Stremio addon discovery**: continuously crawls stremio-addons.net's
  public API + Cinemeta's community addon catalog and registers stream addons as
  sources. Only direct http(s) streams are ever shown/played — torrent and
  external-player streams are counted and hidden; >5 GB (your cap) dropped.
  Detail pages get "⚡ Find streams" with a FastStream Race (parallel 384 KB
  throughput measurement, fastest wins). Your generated DebridStream manifest is
  pinned as an addon source automatically.
- **FastStream embedded for any direct stream**: direct files/streams play inside
  the official FastStream web player (faststream.online, fragmented parallel
  downloading) embedded in-app — no external players, "Open original" removed.
  Built-in HTML5 fallback (4-way parallel-range priming + live MB/s HUD) one tap
  away. The 3 embed players keep FastStream race/preload/failover around them
  (their cross-origin internals can't be accelerated by any host page).
