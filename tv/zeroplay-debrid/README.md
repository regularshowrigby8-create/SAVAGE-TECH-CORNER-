# ZeroPlay

ZeroPlay is a keyless movie and series web app with continuous server/addon
discovery. It uses TMDB when the deployment provides a server-side TMDB
credential, otherwise it automatically uses the keyless Cinemeta catalogue
with real artwork. Visitors never enter an API key.

> Real-Debrid features and user API-key setup have been removed.

## Continuous searcher

Open **Sources**. In the normal path it says only **Ready**, **Searching**,
**Found sources**, or **Problem**; use **Find more** to run the same engine.
**Settings → Advanced sources** contains crawler choices, the provider list
and detailed diagnostics. While the app is open, one engine rotates through independent
crawlers:

- Recursive Stremio `addon_catalog` graph crawling, starting with Cinemeta and
  continuing through catalogues exposed by newly identified addons.
- The public, paginated stremio-addons.net API, including addon instances.
- Keyless GitHub repository search with rotating queries/pages and automatic
  rate-limit backoff.
- GitHub manifest/server probing (homepage, GitHub Pages, README manifest
  links and common repository manifest paths).
- Provider/source JSON feeds and user-added discovery seeds.
- Website-server profile refresh.
- A server-identification queue for candidates found by the other crawlers.
- Rotating health-check batches.

ZeroPlay does not impose a provider-registry cap. The provider list is merely
rendered in pages for browser performance. Browser storage, CORS, upstream
availability and public API rate limits remain external limits.

## Server identification before installation

Finding a URL is not enough. The crawler identifies what the endpoint is
before installing it. For a Stremio-style server it validates the manifest and
records:

- server/addon name and version;
- declared resources (`stream`, `catalog`, `meta`, `subtitles`,
  `addon_catalog`);
- movie/series support;
- catalogue ID prefixes such as IMDb `tt` or `tmdb`;
- searchable catalogues;
- episode capability;
- identification confidence and the checks that passed.

Providers are auto-installed by capability into Streams, Catalogues,
Metadata, Subtitles, Discovery, Websites, Projects and Feeds. GitHub projects
remain candidates until a hosted declarative endpoint/manifest is identified;
arbitrary repository code is never executed. Any provider can be enabled,
disabled or removed while the searcher runs. Removed providers are
tombstoned so the crawler does not immediately resurrect them.

## Built-in title identifier

Discovery identifies the **server**. Playback search separately identifies
the **title**. A catalogue item becomes a canonical identity containing:

- TMDB ID and IMDb ID;
- movie/series type;
- canonical, original and alternative titles;
- release year;
- season and episode.

All enabled players, website servers and identified stream providers are
searchable from **Servers with this title**. Providers may be queried by exact
IMDb/TMDB ID or through an identified searchable catalogue. Returned website
pages, catalogue metas and direct streams are scored against the canonical
identity. Different catalogue IDs or explicit wrong season/episode markers are
hard rejections.

The server drawer does not dump the whole registry into the player. Direct
providers appear only after they return identity-verified streams for the
selected movie/episode, grouped by server with their actual returned quality
variants. Added websites appear only after an identity-verified lookup match.
Built-in/custom embed templates that can address the exact catalogue ID are
shown in a separate **Exact-ID players** section: a hosting page cannot prove
a cross-origin player's internal library before that player loads, so ZeroPlay
does not call those routes verified. The drawer shows search/rejection counts
while results arrive progressively.

Website servers are lookup-only: ZeroPlay does not import their titles or
covers. The existing catalogue title remains canonical, and the website is
searched for that exact title/episode when playback is requested.

## TV browsing experience

- The floating top bar includes Home, Movies, Series, Categories, Sources,
  Downloads, Settings and Search, plus a slide-in menu.
- `/categories` provides movie and series genre tiles; category chips and
  deep-linkable `?genre=` browse shelves keep the selected category in the URL.
- Home includes category rails in addition to Continue Watching, Watchlist,
  Trending, Popular and Top Rated shelves.
- Cinemeta trailer metadata is preserved on catalogue cards. A muted trailer
  can appear inside the focused/hovered poster block, the featured hero and
  the title detail backdrop. Rail/page scrolling starts a preview only after
  scrolling settles and only when a trailer key is already known.
- Trailer previews are one-at-a-time and respect the playback setting, Data
  Saver, save-data/2G connections, reduced-motion preferences, tab visibility
  and an already-open player.
- TV-style arrow-key spatial navigation moves predictably between cards,
  buttons, menus and player controls. `Enter` opens a title and `P` quick-plays
  a focused card.

### Android TV / Xiaomi TV Box S (3rd Gen)

The separate app in `tv/android-tv` targets the MDZ-32-AA box's 32-bit
`armeabi-v7a` userspace and 2 GB RAM. On that shell, ZeroPlay changes from a
dense web layout into a couch-first interface:

- an existing Continue Watching row is shown before the featured hero for
  instant resume;
- poster rows and category artwork are much larger, while ratings/year text,
  filter forms, text category lists, extra hero actions and footer chrome are
  removed from the normal path;
- Sources and Settings open one useful job at a time, with source counters and
  playback internals behind Advanced/Diagnostics;
- native Back, Menu, Search and media keys are forwarded predictably, and
  trim-memory signals stop previews/nonessential artwork work;
- posters/backdrops use TV-sized renditions and are completed automatically
  by keyless Cinemeta/MetaHub, TVmaze, AniList and Apple iTunes endpoints.
  No visitor artwork API key exists or is required.

Build and verification details are in `../android-tv/README.md`.

## Settings and switches

The menu drawer has a deliberately short **Quick settings** list: automatic
source search, trailer previews, failover, downloads, autoplay-next, missing
poster lookup, and (inside the TV shell) the lighter-TV performance switch.
The full Settings page retains the less common controls. The full **Settings** page (`/settings`)
contains the same shared settings plus:

- **Add Website** for lookup-only website servers and optional advanced URL
  templates;
- the Advanced sources controls, discovery seeds, crawler choices, provider
  feed and diagnostics;
- separate switches for trailers, automatic switching, confirmed-stall
  switching, Embedwave automatic failover, FastStream preload/API probing,
  DNS warm-up, health sorting, downloads, multithreading, M3U extraction,
  download auto-resume/auto-save/link-refresh/retention, autoplay-next,
  reduced motion, compact cards and the category strip;
- FastStream configuration for the direct-stream engine, preload mode,
  preferred quality, default source, buffer target, embed-load and
  confirmed-stall timing, safe range-priming workers/chunks/budget, the file
  cap and Data Saver;
- download defaults for threads, part size and concurrent jobs;
- settings export/import and playback health/server-cache clearing.

Switch changes save immediately and synchronize between the menu, Settings,
player and Downloads. The numeric FastStream/download controls are clamped
to safe ranges when read and when saved.

## Downloads and M3U extraction

A **Download** button appears on the title/detail actions, beside every
episode in the episode selector, in the player, and beside downloadable
direct variants in **Servers with this title**. Episode download buttons use
that episode's exact canonical identity; they never reuse whichever episode
happens to be selected for playback.

Downloads are offered only when an identified server returns a direct media
file or an M3U/HLS playlist for the exact movie/episode. The **Downloads**
page (`/downloads`) is the job manager and M3U extractor:

- Known-size files with genuine HTTP Range/`206` support download in parallel
  chunks (1–8 configured threads). Completed chunks are stored in IndexedDB,
  so Pause and a browser restart can resume only the missing chunks.
- HLS media playlists download independently fetchable segments in parallel
  and assemble them in playlist order, including ordered `EXT-X-MAP` sections
  and byte-range segments. Fetchable AES-128 segments are decrypted in the
  browser.
- If a host does not confirm Range support, ZeroPlay saves sequential parts
  and attempts a validated range resume. If the host restarts with HTTP 200
  or returns the wrong offset, ZeroPlay says so instead of splicing unlike
  responses together.
- Expired direct links can be re-found only when the replacement file has the
  same known size; expired HLS links only when the segment path sequence is
  the same. Otherwise the job stops safely.
- Completed jobs can be saved to the device or played from the assembled
  browser file when the browser supports that container. Jobs can be paused,
  resumed, retried, copied, deleted or cleared in bulk.
- The M3U extractor accepts a website/watch page, a hosted M3U/M3U8 URL, or
  pasted playlist text. It lists variants, segment counts and playlist
  duration, and can inspect, copy, play or download a hosted playlist. A
  pasted media playlist without its real hosted URL is parsed locally only;
  ZeroPlay does not pretend the app URL is a downloadable playlist.
- The configured file cap (hard maximum 5 GB) is checked before download when
  size or HLS bandwidth/duration makes it estimable, and while unknown-size
  parts are being stored.

Cross-origin embedded players cannot be downloaded from the parent page.
When only embeds are returned, ZeroPlay explains that limitation instead of
offering a dead download button.

## Playback rules

- VidStuck, ZXCStream and Embedwave remain the built-in embed focus.
- Embedwave is always last resort. Automatic failover into Embedwave is off
  by default and has its own switch; without it, ZeroPlay stops and lets the
  user choose Embedwave manually from Servers.
- An embed iframe merely loading is **not** treated as successful playback.
  ZeroPlay separates “player page loaded” from provider progress messages
  whose timestamp actually advances. A page that never loads can fail over
  after the configured 15–60 second timeout; a loaded VidStuck/ZXCStream page
  is left alone for its own server checks/Play button, and only confirmed
  timestamp progress can start the 30–120 second stall clock. Embed referrer
  policy matches the upstream players (`no-referrer` for Embedwave,
  `strict-origin-when-cross-origin` for the others).
- Every playback mode opens in the same ZeroPlay shell. The shell keeps Back,
  Servers, Details, episode controls and Close in the parent page so a
  cross-origin iframe cannot trap the whole interface.
- Native and FastStream-API playback expose ZeroPlay play/pause, seek, ±10
  seconds, volume/mute, playback speed, quality, PiP and fullscreen controls.
  Cross-origin embeds expose an explicit **Video controls** /
  **Site controls · experimental** focus button because their internal
  controls cannot be driven from the parent page.
- Source strategy by kind: direct files use the native engine; HLS/DASH use
  FastStream through its EmbedAPI when the hosted build sends `ready`
  (subscribed and driven with `load`, seek, volume, rate, PiP and
  `getLevels`/`setVideoLevel`), with the native engine covering
  WebView-playable HLS while the API is probed in the background; embed
  pages keep their own UI. On Android TV this becomes the **TV shell**: our
  control bar is the only D-pad focus target, player frames are
  `tabindex="-1"` and pointer-blocked, the mouse-driven hosted FastStream
  surface never mounts, DASH without a working control API fails to an
  honest Servers prompt, and embed sites are marked **EXPERIMENTAL ON TV**.
  On desktop, **Auto**, **ZeroPlay responsive player** and
  **FastStream web player only** remain selectable in
  **Settings → Advanced playback**.
- Quality selection is real where the engine exposes it: FastStream levels
  for API playback and actual returned direct-stream variants for file
  playback. A single fixed file has only its source quality. For third-party
  embeds the selector records a preference and says that the embed chooses its
  actual internal quality.
- Switching a direct quality/server preserves the current timestamp where the
  active engine reports it.
- Failover searches the title-specific server set instead of cycling blindly
  through every registered source. The automatic-switching master switch also
  gates automatic direct-variant fallback; manual quality/server choices are
  unaffected. Native direct playback uses the same confirmed-progress rule
  before a stall can trigger switching.
- Progress persistence and playback UI updates are throttled. Direct priming
  accepts only genuine `206` range responses and uses the configured
  FastStream limits (1–4 workers, chunk size, data budget and buffer target).
  Continuous discovery cycles are deferred while the player is open.
- Torrent and external-player streams are hidden.
- Known direct files over the selected cap are rejected; the hard maximum is
  5 GB.
- No external players are used.

## Catalogue and keys

There are no user API-key fields. A browser cannot safely mint valid
third-party account keys, so ZeroPlay uses keyless public APIs and, for TMDB,
only an optional deployer-owned server environment secret:

- `TMDB_API_KEY`, or
- `TMDB_ACCESS_TOKEN`.

If neither deployment variable exists, Cinemeta is used automatically. The
GitHub, stremio-addons.net and Cinemeta crawlers require no visitor key.

## Deployment

### Vercel

Use this folder as the project root (`tv/zeroplay-debrid` in this repository).
The serverless functions are:

- `api/tmdb.js` — optional server-side TMDB proxy;
- `api/extract.js` — guarded website lookup, JSON relay, validated playlist
  text (`mode=text`) and page/playlist M3U extraction (`mode=m3u`). Remote
  documents are read with a streamed 1.5 MB safety cap; oversized playlists
  are refused for `mode=text` instead of being silently truncated.

### Netlify

`netlify.toml` publishes this folder and routes `/api/tmdb` and
`/api/extract` to the matching functions in `netlify/functions/`.

An optional TMDB environment variable can be set by the deployer; it is not
required for the live keyless catalogue.

## Tests

```bash
npm test
npm run build
```

`npm test` runs identity/discovery, crawler, regression, TV/player, and
download/M3U/settings suites. The download suite exercises the real inline
functions with mocked range/HLS responses rather than only checking that
buttons exist.

## Honest limitations

- Continuous crawling runs while the web app/tab (or a future server worker)
  is running.
- A discovered GitHub repository without a hosted endpoint cannot be turned
  into a working in-browser server merely from source code.
- A website that requires login, CAPTCHA, DRM, private APIs or JavaScript-only
  private routes may not expose a usable public result.
- FastStream/ZeroPlay cannot control quality or internals inside unrelated
  cross-origin embed players.
- Exact-ID embed routes are addressable playback routes, not proof that the
  third-party player's internal catalogue contains a title; verified direct
  and website matches are therefore labelled separately.
- Trailer previews depend on catalogue trailer metadata and YouTube embed
  availability; titles without a trailer keep their poster/backdrop.
- The 5 GB cap is exact when usable size metadata/headers exist; otherwise
  the app labels size as unknown instead of inventing it. Unknown-size
  downloads are stopped as stored bytes reach the configured cap.
- Browser downloads require the remote host to permit CORS. Parallelism and
  pause/resume additionally require HTTP Range/`206` responses or
  independently fetchable HLS segments. Browser/IndexedDB quota can still
  limit how much can be stored; if IndexedDB is unavailable, the in-memory
  fallback cannot survive a browser restart.
- HLS downloads do not support DRM or SAMPLE-AES, live playlists without an
  end marker cannot be completed as one file, separate audio renditions are
  not muxed into the selected video playlist, and DASH is playback-only in
  this downloader. An assembled `.ts` may need to be saved and played by a
  media player that supports MPEG-TS if the browser itself cannot play it.
- Public crawlers can be rate-limited or changed by their operators.

ZeroPlay hosts no media files. This product uses the TMDB API when configured
but is not endorsed or certified by TMDB.
