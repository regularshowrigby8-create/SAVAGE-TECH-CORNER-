# ZeroPlay

ZeroPlay is a keyless movie and series web app with continuous server/addon
discovery. It uses TMDB when the deployment provides a server-side TMDB
credential, otherwise it automatically uses the keyless Cinemeta catalogue
with real artwork. Visitors never enter an API key.

> Real-Debrid features and user API-key setup have been removed.

## Continuous searcher

Open **Discover** and use the single **Start continuous search** button. While
the app is open, one engine rotates through independent crawlers:

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
searchable from **Find streams**. Providers may be queried by exact IMDb/TMDB
ID or through an identified searchable catalogue. Returned website pages,
catalogue metas and direct streams are scored against the canonical identity.
Different catalogue IDs or explicit wrong season/episode markers are hard
rejections. Only identity-verified candidates are offered for playback, and
the modal shows the identity evidence and rejection counts.

Website servers are lookup-only: ZeroPlay does not import their titles or
covers. The existing catalogue title remains canonical, and the website is
searched for that exact title/episode when playback is requested.

## Playback rules

- VidStuck, ZXCStream and Embedwave remain the built-in embed focus.
- Embedwave is always last resort.
- Direct media opens in the embedded FastStream player by default.
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
- `api/extract.js` — guarded website lookup and JSON relay.

### Netlify

`netlify.toml` publishes this folder and routes `/api/tmdb` and
`/api/extract` to the matching functions in `netlify/functions/`.

An optional TMDB environment variable can be set by the deployer; it is not
required for the live keyless catalogue.

## Honest limitations

- Continuous crawling runs while the web app/tab (or a future server worker)
  is running.
- A discovered GitHub repository without a hosted endpoint cannot be turned
  into a working in-browser server merely from source code.
- A website that requires login, CAPTCHA, DRM, private APIs or JavaScript-only
  private routes may not expose a usable public result.
- FastStream cannot control quality or internals inside cross-origin embed
  players.
- The 5 GB cap is exact when usable size metadata/headers exist; otherwise
  the app labels size as unknown instead of inventing it.
- Public crawlers can be rate-limited or changed by their operators.

ZeroPlay hosts no media files. This product uses the TMDB API when configured
but is not endorsed or certified by TMDB.
