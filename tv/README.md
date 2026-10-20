# TV — ZeroPlay prototype

This folder is the working area for the TV project.

| Path | What it is |
| --- | --- |
| `upstream-zeroplay/` | Unmodified sparse reference clone of ZeroPlay (`web/`, `api/` and root files). |
| `zeroplay-debrid/` | The deployed web prototype. The directory name is historical; Real-Debrid features have been removed from the app. |

**Live prototype:** https://zeroplay-rd.vercel.app
(Vercel project `zeroplay-rd`, root directory `tv/zeroplay-debrid`.)

**Status:** web prototype for review. Xiaomi TV Box 3rd-generation adaptation
remains a later phase after user approval.

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

See `zeroplay-debrid/README.md` for the full architecture, deployment and
limitations.
