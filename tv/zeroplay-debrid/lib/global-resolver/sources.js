"use strict";
/**
 * The part the batches never wrote: what a source actually *is*, and how to ask
 * it for a stream.
 *
 * `buildSourceSeeds()` in resolver.js treated every registered source as a web
 * page and pushed the source URL itself as a seed. For an addon-manifest source
 * that meant fetching `/manifest.json` and then scraping the manifest for video
 * links — which finds nothing, every time. A Stremio addon is not a page: you
 * read its manifest once to learn what it can do, then you call
 * `/stream/{type}/{id}.json` with the real catalogue id.
 *
 * With no sources registered this mattered even more: zero seeds, zero results,
 * and the UI sat on "Searching…" forever with nothing to search.
 */

/* Stremio catalogue ids are `tt0903747` and `tt0903747:1:2`; the colons are part
   of the protocol and must stay literal. encodeURIComponent turns them into
   %3A and every mainstream addon 404s, which is a bug this project already hit
   once in the feed layer. */
function streamSegment(value) {
  return encodeURIComponent(String(value || "")).replace(/%3A/gi, ":");
}

function normalizeBase(url) {
  const text = String(url || "").trim();
  if (!text) return "";
  return text.replace(/\/+$/, "").replace(/\/manifest\.json$/i, "");
}

/** Does this manifest claim a stream resource for the requested type? */
function manifestSupports(manifest, type) {
  if (!manifest || typeof manifest !== "object") return false;
  const resources = Array.isArray(manifest.resources)
    ? manifest.resources.map((entry) => (typeof entry === "string" ? entry : entry && entry.name)).filter(Boolean)
    : [];
  if (!resources.includes("stream")) return false;
  const types = Array.isArray(manifest.types) ? manifest.types : ["movie", "series"];
  const wanted = type === "tv" || type === "series" ? ["series", "tv"] : ["movie"];
  return types.some((entry) => wanted.includes(String(entry).toLowerCase()));
}

/** The protocol id a stream request needs, from whatever identity we hold. */
function catalogueId(identity) {
  const item = identity || {};
  const type = String(item.type || "").toLowerCase();
  const isSeries = type === "tv" || type === "series" || (item.season != null && item.episode != null);
  if (item.imdbId && /^tt\d+/.test(String(item.imdbId))) {
    const base = String(item.imdbId).split(":")[0];
    if (!isSeries) return base;
    const season = Number(item.season) || 1;
    const episode = Number(item.episode) || 1;
    return base + ":" + season + ":" + episode;
  }
  if (item.tmdbId) {
    const base = "tmdb:" + String(item.tmdbId);
    if (!isSeries) return base;
    return base + ":" + (Number(item.season) || 1) + ":" + (Number(item.episode) || 1);
  }
  return "";
}

function streamRequestType(identity) {
  const type = String((identity && identity.type) || "").toLowerCase();
  if (type === "tv" || type === "series") return "series";
  return "movie";
}

/**
 * Turn any Stremio-style stream entry into something the player can use, or
 * null with a reason. Mirrors the feed layer's rules so the two finders cannot
 * disagree about what is playable.
 */
function streamEntryToResult(entry, context) {
  const item = entry || {};
  const source = context || {};
  const reject = (reason) => ({ ok: false, reason });
  if (!item || typeof item !== "object") return reject("not-an-object");

  if (item.infoHash) return reject("torrent-only");
  if (item.nzbUrl) return reject("usenet-only");
  if (item.ytId) return reject("youtube-only");
  if (item.externalUrl && !item.url) return reject("external-page");

  const url = String(item.url || "").trim();
  if (!url) return reject("no-url");
  if (/^(magnet:|ed2k:|thunder:)/i.test(url)) return reject("magnet");
  if (/\.torrent(\?|$)/i.test(url)) return reject("torrent-file");

  const hints = item.behaviorHints || {};
  if (hints.notWebReady === true) return reject("not-web-ready");
  if (hints.proxyHeaders && typeof hints.proxyHeaders === "object" && Object.keys(hints.proxyHeaders).length) {
    return reject("proxy-headers");
  }

  const path = url.split("?")[0].split("#")[0].toLowerCase();
  let kind = "direct-file";
  if (/\.m3u8?$/.test(path)) kind = "hls-playlist";
  else if (/\.mpd$/.test(path)) kind = "dash-manifest";
  else if (!/\.(mp4|m4v|mov|webm|mkv|avi|ts)$/.test(path)) {
    /* An extensionless URL is fine for HLS behind a CDN, but a page is not a
       stream. Refuse anything that looks like a document. */
    if (/\/(watch|embed|player|page)\//i.test(path)) return reject("page-not-stream");
  }

  const name = String(item.name || source.name || "Stream");
  const title = String(item.title || item.description || "");
  return {
    ok: true,
    result: {
      url,
      resultKind: kind,
      title: title || name,
      sourceName: name,
      sourceId: source.id || "",
      quality: guessQuality(name + " " + title),
      verified: source.verified === true,
      readyToUse: true,
      origin: "global"
    }
  };
}

function guessQuality(text) {
  const match = /(\d{3,4})p|4k|2160p|1080p|720p/i.exec(String(text || ""));
  if (!match) return "";
  const found = (match[1] || match[0]).toLowerCase();
  if (found === "4k") return "2160p";
  return found.endsWith("p") ? found : found + "p";
}

/**
 * A source list that needs no configuration.
 *
 * The rule from the spec is "no user configuration required", and the resolver
 * shipped with zero sources — so it could never return anything. These are
 * public, keyless, CORS-open endpoints the app already trusted elsewhere
 * (Cinemeta is the app's own catalogue fallback). Addons are probed once for a
 * stream resource before being kept, so a dead default costs one request and
 * then silence, not a blocked search.
 */
const DEFAULT_ADDON_SOURCES = Object.freeze([
  { url: "https://v3-cinemeta.strem.io", name: "Cinemeta", priority: 90 },
  { url: "https://opensubtitles-v3.strem.io", name: "OpenSubtitles", priority: 20 },
  { url: "https://torrentio.strem.fun", name: "Torrentio", priority: 10, // stream-only, torrents refused later
    skip: true }
]);

/* Which defaults to actually try: torrent-only addons are pointless because the
   guard refuses their results anyway, so they are not registered at all. */
function usableDefaults() {
  return DEFAULT_ADDON_SOURCES.filter((source) => !source.skip);
}

module.exports = {
  streamSegment,
  normalizeBase,
  manifestSupports,
  catalogueId,
  streamRequestType,
  streamEntryToResult,
  guessQuality,
  DEFAULT_ADDON_SOURCES,
  usableDefaults
};
