# TV — ZeroPlay prototype

This folder is the working area for the TV project.

| Path | What it is |
| --- | --- |
| `upstream-zeroplay/` | Unmodified sparse reference clone of ZeroPlay (`web/`, `api/` and root files). |
| `zeroplay-debrid/` | The deployed web app. The directory name is historical; Real-Debrid features have been removed from the app. |
| `android-tv/` | Dependency-light Leanback shell and ARMv7 APK project for the Xiaomi TV Box S (3rd Gen). |

**Live prototype:** https://zeroplay-rd.vercel.app
(Vercel project `zeroplay-rd`, root directory `tv/zeroplay-debrid`.)

**Status:** the web app now has a couch-first Android TV mode, and the
separate Leanback APK project targets the Xiaomi TV Box S (3rd Gen) 32-bit
`armeabi-v7a` userspace. See `android-tv/README.md` for build and sideloading
details.

## Current discovery design

- Visitors never enter API keys. TMDB is used only if the deployment provides
  a server-side `TMDB_API_KEY` or `TMDB_ACCESS_TOKEN`; otherwise the keyless
  Cinemeta catalogue supplies real posters/metadata.
- One continuous-search button starts independent crawlers for recursive
  addon catalogues, stremio-addons.net, GitHub projects, feeds/seeds,
  websites, candidate server identification and health.
- The crawler identifies a server/addon before installing it: manifest,
  resources, movie/series support, ID prefixes, searchable catalogues and
  episode capability are recorded by category.
- GitHub projects are not executed. They become installed providers only when
  a hosted declarative endpoint/manifest is identified.
- The provider registry has no ZeroPlay-imposed cap. Providers can be
  filtered, searched, enabled, disabled and removed while the crawler runs;
  the UI paginates rows only for rendering performance.
- At playback time, the built-in title identifier creates a canonical identity
  from TMDB/IMDb IDs, title variants, year, type and season/episode. Website
  results, catalogue metas and direct streams must pass that identifier.
- Added websites remain lookup-only: no titles or covers are imported.
- VidStuck/ZXCStream/Embedwave remain built-in, Embedwave is last resort,
  direct media uses embedded FastStream, torrents are hidden, and known files
  over the hard 5 GB ceiling are rejected.
- The normal Sources/Settings path now leads with one of four states —
  Ready, Searching, Found sources, or Problem. Detailed registry counts,
  crawlers and playback internals are under Advanced/Diagnostics. The menu's
  Quick settings list is intentionally short; the full Settings page retains
  Add Website, downloads, import/export and the less common switches.
- Title/detail, every episode row, the player and verified direct server
  rows expose Download actions. The Downloads page manages pause/resume jobs
  and M3U extraction; parallel work is used only for genuine Range files or
  independent HLS segments, with the limitation stated when a host supports
  neither.

## Android TV experience

- The Xiaomi target build is a split `armeabi-v7a` APK; the verifier rejects
  arm64-only native libraries even though the shell normally bundles none.
- Continue Watching comes before the hero in the TV shell. Rows are large,
  artwork-led and text-light; text category lists and browse filters are not
  part of the normal TV path.
- Native Back/Menu/Search/media keys, launch focus, focus restoration and
  trim-memory handling are wired between the shell and web app.
- Posters/backdrops are completed with zero user configuration from
  Cinemeta/MetaHub, TVmaze, AniList and Apple iTunes; candidates are
  identity-matched and image-probed, then cached at TV-appropriate sizes.

## TV browsing and player polish

- A menu drawer, top menu bar, category strip, category shelves and a
  dedicated Categories page provide TV-style browsing without replacing the
  approved ZeroPlay layout.
- Poster title blocks, the home hero and the detail backdrop can preview a
  muted trailer inline after hover, remote/keyboard focus or scroll settling.
  Only one preview runs at a time; Data Saver, reduced motion and slow/save-data
  connections disable it.
- Arrow-key spatial navigation and strong focus states support TV remotes.
- Playback uses one ZeroPlay shell with Back, Servers, Details, play/pause,
  seek, ±10 seconds, volume, speed, quality, PiP and fullscreen where the
  active engine permits them.
- **Servers with this title** progressively lists only direct/website servers
  that return the selected movie or exact episode, grouped by server and real
  quality variant. Built-in/custom players that can merely address the exact
  TMDB/IMDb ID are shown separately as exact-ID routes, not falsely labelled
  verified.
- FastStream is integrated through its official EmbedAPI when the hosted
  player advertises `ready`: ZeroPlay controls then drive FastStream and its
  `getLevels` quality menu. The currently hosted player lags that API, so Auto
  mode does not delay playable files: MP4/WebM use the responsive ZeroPlay
  engine while capability is probed in the background, and HLS/DASH use the
  embedded FastStream player inside the same shell.
- Player responsiveness protections include throttled progress writes,
  parent-shell focus retention, safe 206-only range priming with configurable
  1–4 worker/chunk/budget/buffer limits, cancellable server searches, and
  pausing continuous discovery cycles while video is open.
- Loading an embed page is not counted as playback success. VidStuck and
  ZXCStream are given time for their own server checks after the frame loads;
  only a frame that never loads, a failed resolve, or a stall after real
  timestamp progress can fail over. Automatic Embedwave failover is a
  separate opt-in switch and is off by default.

See `zeroplay-debrid/README.md` for the full architecture, deployment and
limitations.
