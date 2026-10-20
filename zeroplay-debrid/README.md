# ⚡ ZeroPlay RD — ZeroPlay in the DebridStream UI

A **single-file web app** that takes [ZeroPlay 2.2 Web](https://github.com/Zer0Spce/ZeroPlay)
(TMDB discovery, VidStuck / ZXCStream / Embedwave playback, watchlist, continue-watching)
and rebuilds its interface in the design language of
[DebridStream](https://github.com/brett0x0/debrid-stream) — GitHub-dark surfaces
(`#0d1117` / `#161b22`), violet primary (`#8a2be2`), pill logo-badge, checkbox-cards,
status boxes and the full Real-Debrid **configure** experience (16 providers, sorting &
limits, resolutions, `[RD+]` cached-only toggle, Install-to-Stremio manifest generator).

> **The deliverable is [`index.html`](index.html)** — one self-contained file
> (all CSS + JS inline, zero dependencies) that you can launch on Netlify as-is.

---

## 🚀 Launch on Netlify

### Option A — Drag & drop (fastest, ~30 seconds)

1. Download **`zeroplay-debrid-netlify.zip`** from this repo (or just grab `index.html` alone — it works by itself).
2. Go to **[app.netlify.com/drop](https://app.netlify.com/drop)** and drop the zip/file.
3. Done — you get a live `*.netlify.app` URL.

With drag & drop there is no server-side TMDB key, so the app opens in **demo catalog**
mode (playback already works). For the live catalog, open **⚡ Debrid → section 5** in the
app and paste your free TMDB API key (stored only in your browser).

### Option B — Git deploy (recommended: live catalog for every visitor)

1. Push this repo to GitHub (this folder lives at `zeroplay-debrid/`).
2. In Netlify: **Add new site → Import an existing project** → pick the repo.
3. Set **Base directory** to `zeroplay-debrid`. Build command: *(none)*. Publish directory: `.`
   (all of this is already in [`netlify.toml`](netlify.toml)).
4. **Site settings → Environment variables** → add **`TMDB_API_KEY`** =
   your TMDB v3 key (free at [themoviedb.org/settings/api](https://www.themoviedb.org/settings/api))
   — or `TMDB_ACCESS_TOKEN` for a v4 bearer token.
5. Deploy. The browser now uses `/api/tmdb` (the included Netlify Function keeps your
   key server-side and only forwards allowlisted TMDB routes — same design as ZeroPlay's
   own `web/functions/tmdb.js`), and Real-Debrid validation goes through `/api/debrid`.

### Option C — Netlify CLI

```bash
cd zeroplay-debrid
npx netlify deploy --prod --dir=. --functions=netlify/functions
npx netlify env:set TMDB_API_KEY your_tmdb_v3_key
```

---

## 📂 What's in the folder

| File | Purpose |
| --- | --- |
| **`index.html`** | The whole app — ZeroPlay engine + DebridStream UI in one file (~81 KB). Works standalone. |
| `netlify.toml` | Publish/functions config, `/api/tmdb` + `/api/debrid` rewrites, `/movie/123` deep links, CSP + security headers. |
| `netlify/functions/tmdb.js` | TMDB proxy (ZeroPlay's allowlist validator, inlined). Reads `TMDB_API_KEY` / `TMDB_ACCESS_TOKEN`. |
| `netlify/functions/debrid.js` | Real-Debrid token validation proxy — mirrors DebridStream's `POST /api/validate-token` response. Token is never logged/stored. |
| `package.json` | Pins CommonJS for the functions inside this folder. |
| `zeroplay-debrid-netlify.zip` | Ready-made drag-and-drop bundle of the above. |

## 🎬 What the app does

- **Home** — featured hero + rails: Continue Watching, Watchlist, Trending `[RD+]`,
  Popular Movies/Series, Top Rated, Now Playing.
- **Movies / TV Series** — full-catalog browsing with genre / year / rating / sort filters
  and in-place “Load more” (deduplicated, like ZeroPlay).
- **Search** — TMDB multi-search with All / Movies / TV chips. Press `/` anywhere to focus search.
- **Title pages** — synopsis, genres, rating, cast, YouTube trailer, recommendations,
  and for series a season/episode picker that remembers where you left off.
- **Player** — ZeroPlay's exact embed logic for **VidStuck** (branding/subtitle/next-episode
  params + resume `progress`), **ZXCStream (Beta)** and **Embedwave (BETA)**, with in-player
  source switching and episode prev/next. Playback progress reported by the embed
  (`PLAYER_EVENT`, origin-validated) feeds Continue Watching automatically.
- **My Library** — watchlist + continue-watching + history, 100% local (localStorage).
- **⚡ Debrid** — DebridStream's configure page, faithfully ported:
  1. Real-Debrid API key + live **Validate Key** (via `/api/debrid`, falling back to the RD API directly),
  2. all 16 torrent providers as checkbox-cards (Mainstream / Anime / Regional),
  3. sorting & limits + `[RD+]` cached-only toggle,
  4. resolutions (4K → SD),
  5. ZeroPlay catalog & playback settings (TMDB key test, default source, your DebridStream server URL).
  **🚀 Install Addon to Stremio** builds a `{your-server}/{config}/manifest.json` URL using
  DebridStream's accepted base64url-JSON config format and fires the `stremio://` handler;
  **📋 Copy Manifest URL** does the same to your clipboard.

### Catalog modes (automatic, in order)

1. **Your pasted TMDB key** (⚡ Debrid → 5) → browser talks to TMDB directly (v3 key or v4 token).
2. **Netlify env key** → browser uses the `/api/tmdb` function proxy.
3. **Neither** → built-in 18-title **demo catalog** with generated artwork. Playback, details
   and episodes still work so you can evaluate the whole UI before adding keys.

## ⚠️ Honest notes

- **Web playback uses ZeroPlay's embed sources** (VidStuck/ZXCStream/Embedwave), exactly like
  ZeroPlay 2.2 Web. Real-Debrid powers the **Stremio addon** side: the `[RD+]` stream
  resolution itself runs on your self-hosted
  [debrid-stream](https://github.com/brett0x0/debrid-stream) server (Docker, port 7000) —
  a static Netlify page can't scrape providers or un-restrict RD links. This page is its
  configure UI + a full web player in one.
- **Manifest links from this static page are plaintext base64url JSON** (DebridStream's
  decoder accepts them). Anyone holding the link can read the RD token inside — treat it
  like a password. For AES-256-GCM-encrypted links, use your server's own `/configure`.
- API keys you paste are kept in **localStorage on your device only** and sent solely to
  TMDB / Real-Debrid (or your own Netlify functions). Prefer the Netlify env var for shared sites.
- The single-file build uses `script-src 'unsafe-inline'` (required for inline JS).
  Everything else in the CSP is locked to TMDB, Real-Debrid and the three embed hosts.
- ZeroPlay's Live TV / PPV / Sports are native-app features and are intentionally
  absent from the web build, same as upstream.

## 🙏 Credits

- [ZeroPlay](https://github.com/Zer0Spce/ZeroPlay) by Zer0Spce — discovery, playback-source
  and progress logic (verified byte-identical embed URLs against `playback-sources.js`).
- [DebridStream](https://github.com/brett0x0/debrid-stream) by brett0x0 — interface design
  language and configure-form structure.
- Metadata & artwork by [TMDB](https://www.themoviedb.org). This product uses the TMDB API
  but is not endorsed or certified by TMDB. This site hosts no media files.
