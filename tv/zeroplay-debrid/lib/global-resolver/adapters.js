"use strict";
const { URL, URLSearchParams } = require("node:url");
const {
  SOURCE_KINDS, normalizeUrl, guardUrl, classifyUrl, getHostname
} = require("./policy");
const { scoreCandidate, createIdentityContext } = require("./identity");
const SOURCE_ADAPTER_KINDS = {
  HTML_PAGE: "html-page", JSON_API: "json-api", ADDON_MANIFEST: "addon-manifest",
  PUBLIC_CATALOG: "public-catalog", USER_SOURCE: "user-source", UNKNOWN: "unknown"
};
const MAX_HTML_LINKS = 80;
const MAX_JSON_ITEMS = 120;
function detectSourceKind(url, response) {
  const parsedUrl = normalizeUrl(url);
  if (!parsedUrl) return SOURCE_ADAPTER_KINDS.UNKNOWN;
  const contentType = String(response?.contentType || "").toLowerCase();
  const pathname = parsedUrl.pathname.toLowerCase();
  if (pathname.endsWith("/manifest.json") || pathname.endsWith("manifest.json") || contentType.includes("application/vnd.stremio+json")) return SOURCE_ADAPTER_KINDS.ADDON_MANIFEST;
  if (contentType.includes("application/json") || pathname.endsWith(".json")) return SOURCE_ADAPTER_KINDS.JSON_API;
  if (contentType.includes("text/html") || pathname.endsWith(".html") || pathname.endsWith(".htm")) return SOURCE_ADAPTER_KINDS.HTML_PAGE;
  return SOURCE_ADAPTER_KINDS.UNKNOWN;
}
function createGlobalSourceRegistry(options) {
  const config = options || {};
  const sources = new Map();
  const sourceHealth = new Map();
  const maximumSources = clampInteger(config.maximumSources, 10, 10000, 1000);
  const maximumFailuresBeforeCooldown = clampInteger(config.maximumFailuresBeforeCooldown, 1, 20, 4);
  const cooldownMs = clampInteger(config.cooldownMs, 1000, 3600000, 300000);
  return { addSource, getSource, getSources, updateHealth, isSourceAvailable, getStatistics, clear };
  /**
   * A stable, collision-resistant-enough id — without node:crypto.
   *
   * The old version did `require("node:crypto")` *inside* this function, so in
   * the browser the first addSource() threw, the bootstrap swallowed it, and the
   * resolver came up with zero sources. That is the whole reason the finder sat
   * on "Searching…": there was nothing registered to search. Hashing is not
   * needed here — the id only has to be deterministic and unique per source.
   */
  function createSourceId(source) {
    const identity = [source.url, source.name, source.kind].filter(Boolean).join("|");
    let hash = 0x811c9dc5;
    for (let index = 0; index < identity.length; index += 1) {
      hash ^= identity.charCodeAt(index);
      hash = Math.imul(hash, 0x01000193) >>> 0;
    }
    const suffix = hash.toString(16).padStart(8, "0");
    let extra = 0;
    for (let index = 0; index < identity.length; index += 1) {
      extra = (Math.imul(extra, 31) + identity.charCodeAt(index)) >>> 0;
    }
    return "src-" + suffix + extra.toString(16).padStart(8, "0").slice(0, 6) + "-" +
      identity.length.toString(16);
  }
  function addSource(input) {
    if (!input || !input.url) return null;
    const parsedUrl = guardUrl(input.url, { allowTorrents: false });
    if (!parsedUrl) return null;
    if (sources.size >= maximumSources) return null;
    const source = {
      id: createSourceId({ url: parsedUrl.href, name: input.name, kind: input.kind }),
      url: parsedUrl.href, name: input.name || getHostname(parsedUrl),
      kind: input.kind || SOURCE_ADAPTER_KINDS.USER_SOURCE, enabled: input.enabled !== false,
      priority: clampInteger(input.priority || 50, 0, 100, 50), addedAt: Date.now(), updatedAt: Date.now(),
      capabilities: normalizeCapabilities(input.capabilities), metadata: input.metadata || {}
    };
    sources.set(source.id, source);
    sourceHealth.set(source.id, { successes: 0, failures: 0, consecutiveFailures: 0, lastSuccessAt: 0, lastFailureAt: 0, averageLatencyMs: 0, cooldownUntil: 0, lastError: null });
    return source;
  }
  function getSource(sourceId) { return sources.get(sourceId) || null; }
  function getSources(options) {
    const config = options || {};
    return [...sources.values()].filter((source) => {
      if (config.enabledOnly && !source.enabled) return false;
      if (config.kind && source.kind !== config.kind) return false;
      return true;
    }).sort((a, b) => b.priority - a.priority);
  }
  function updateHealth(sourceId, outcome) {
    const source = sources.get(sourceId);
    if (!source) return null;
    const health = sourceHealth.get(source.id) || { successes: 0, failures: 0, consecutiveFailures: 0, lastSuccessAt: 0, lastFailureAt: 0, averageLatencyMs: 0, cooldownUntil: 0, lastError: null };
    const latency = Number(outcome?.latencyMs || 0);
    if (outcome?.success) {
      health.successes += 1; health.consecutiveFailures = 0; health.lastSuccessAt = Date.now(); health.lastError = null;
      if (latency > 0) health.averageLatencyMs = health.averageLatencyMs === 0 ? latency : Math.round((health.averageLatencyMs + latency) / 2);
    } else {
      health.failures += 1; health.consecutiveFailures += 1; health.lastFailureAt = Date.now(); health.lastError = outcome?.error || "unknown-error";
      if (health.consecutiveFailures >= maximumFailuresBeforeCooldown) health.cooldownUntil = Date.now() + cooldownMs;
    }
    sourceHealth.set(source.id, health);
    return { sourceId: source.id, health: { ...health } };
  }
  function isSourceAvailable(sourceId) {
    const source = sources.get(sourceId);
    if (!source || !source.enabled) return false;
    const health = sourceHealth.get(sourceId);
    if (!health) return true;
    return Date.now() >= health.cooldownUntil;
  }
  function getStatistics() {
    const healthEntries = [...sourceHealth.values()];
    return {
      totalSources: sources.size,
      enabledSources: [...sources.values()].filter((s) => s.enabled).length,
      healthySources: healthEntries.filter((h) => h.consecutiveFailures === 0).length,
      coolingDownSources: healthEntries.filter((h) => h.cooldownUntil > Date.now()).length,
      maximumSources, maximumFailuresBeforeCooldown, cooldownMs
    };
  }
  function clear() { sources.clear(); sourceHealth.clear(); }
}
function normalizeCapabilities(input) {
  const capabilities = input || {};
  return {
    movies: capabilities.movies !== false, series: capabilities.series !== false, search: capabilities.search !== false,
    directFiles: capabilities.directFiles !== false, playlists: capabilities.playlists !== false, metadata: capabilities.metadata !== false
  };
}
function normalizeSourceCandidate(input, source) {
  const candidate = input || {};
  return {
    sourceId: source?.id || null, sourceName: source?.name || null, sourceKind: source?.kind || SOURCE_ADAPTER_KINDS.UNKNOWN, sourceUrl: source?.url || null,
    title: candidate.title || candidate.name || "", originalTitle: candidate.originalTitle || candidate.original_title || "",
    aliases: Array.isArray(candidate.aliases) ? candidate.aliases : [],
    imdbId: candidate.imdbId || candidate.imdb_id || candidate.imdb || "",
    tmdbId: candidate.tmdbId || candidate.tmdb_id || candidate.tmdb || "",
    year: candidate.year || candidate.releaseYear || candidate.release_year || "",
    type: candidate.type || candidate.mediaType || "",
    season: candidate.season !== undefined && candidate.season !== null ? Number(candidate.season) : null,
    episode: candidate.episode !== undefined && candidate.episode !== null ? Number(candidate.episode) : null,
    url: candidate.url || candidate.href || candidate.link || "",
    text: candidate.text || candidate.description || candidate.snippet || "",
    kind: candidate.kind || classifyUrl(candidate.url || candidate.href || candidate.link),
    discoveredAt: Date.now()
  };
}
function parseHtmlPage(html, baseUrl, source) {
  if (!html || typeof html !== "string") return { candidates: [], links: [], metadata: {} };
  const parsedBaseUrl = normalizeUrl(baseUrl);
  if (!parsedBaseUrl) return { candidates: [], links: [], metadata: {} };
  const links = extractHtmlLinks(html, parsedBaseUrl.href);
  const title = extractHtmlTitle(html);
  const metadata = extractHtmlMetadata(html);
  const candidates = [];
  if (title) {
    candidates.push(normalizeSourceCandidate({ title, url: parsedBaseUrl.href, text: metadata.description || title, kind: SOURCE_KINDS.DETAIL_PAGE }, source));
  }
  return { candidates, links, metadata };
}
function extractHtmlLinks(html, baseUrl) {
  const links = [];
  const patterns = [
    /<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>/gi,
    /<iframe\b[^>]*src\s*=\s*["']([^"']+)["'][^>]*>/gi,
    /<source\b[^>]*src\s*=\s*["']([^"']+)["'][^>]*>/gi,
    /<video\b[^>]*src\s*=\s*["']([^"']+)["'][^>]*>/gi
  ];
  for (const pattern of patterns) {
    let match;
    while ((match = pattern.exec(html)) !== null && links.length < MAX_HTML_LINKS) {
      const resolvedLink = resolveRelativeUrl(match[1], baseUrl);
      if (resolvedLink) links.push(resolvedLink.href);
    }
  }
  return [...new Set(links)];
}
function resolveRelativeUrl(link, baseUrl) {
  if (!link || !baseUrl) return null;
  try {
    const resolvedUrl = new URL(link, baseUrl);
    return guardUrl(resolvedUrl.href, { allowTorrents: false });
  } catch (error) { return null; }
}
function extractHtmlTitle(html) {
  if (!html) return "";
  const titleMatch = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
  if (titleMatch && titleMatch[1]) return decodeHtmlEntities(titleMatch[1]).trim();
  const headingMatch = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i);
  if (headingMatch && headingMatch[1]) return stripHtmlTags(headingMatch[1]).trim();
  return "";
}
function extractHtmlMetadata(html) {
  const metadata = {};
  if (!html) return metadata;
  const patterns = [
    { key: "description", pattern: /<meta[^>]+name\s*=\s*["']description["'][^>]*content\s*=\s*["']([^"']+)["']/i },
    { key: "description", pattern: /<meta[^>]+property\s*=\s*["']og:description["'][^>]*content\s*=\s*["']([^"']+)["']/i },
    { key: "title", pattern: /<meta[^>]+property\s*=\s*["']og:title["'][^>]*content\s*=\s*["']([^"']+)["']/i }
  ];
  for (const pattern of patterns) {
    const match = html.match(pattern.pattern);
    if (match && match[1]) metadata[pattern.key] = decodeHtmlEntities(match[1]);
  }
  return metadata;
}
function stripHtmlTags(input) { return String(input || "").replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim(); }
function decodeHtmlEntities(input) {
  return String(input || "").replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&nbsp;/g, " ");
}
function parseJsonApi(json, baseUrl, source) {
  if (!json) return { candidates: [], links: [], metadata: {} };
  const candidates = [];
  const items = extractJsonItems(json);
  for (const item of items.slice(0, MAX_JSON_ITEMS)) {
    const normalizedCandidate = normalizeSourceCandidate(item, source);
    if (normalizedCandidate.url) candidates.push(normalizedCandidate);
  }
  return { candidates, links: [], metadata: { itemCount: items.length } };
}
function extractJsonItems(json) {
  if (Array.isArray(json)) return json;
  if (!json || typeof json !== "object") return [];
  const likelyArrayKeys = ["results","items","data","entries","metas","videos","catalog","streams","links"];
  for (const key of likelyArrayKeys) if (Array.isArray(json[key])) return json[key];
  if (json.meta && typeof json.meta === "object") return [json.meta];
  return [];
}
function parseAddonManifest(manifest, baseUrl, source) {
  if (!manifest || typeof manifest !== "object") return { valid: false, candidates: [], links: [], metadata: {} };
  const valid = Boolean(manifest.id) && Array.isArray(manifest.types) && manifest.types.length > 0;
  if (!valid) return { valid: false, candidates: [], links: [], metadata: {} };
  const links = [];
  if (Array.isArray(manifest.catalogs) && manifest.catalogs.length > 0) {
    for (const catalog of manifest.catalogs.slice(0, 10)) {
      const catalogLink = buildCatalogUrl(baseUrl, catalog);
      if (catalogLink) links.push(catalogLink.href);
    }
  }
  return {
    valid: true, candidates: [], links,
    metadata: {
      addonId: manifest.id, addonName: manifest.name, types: manifest.types, resources: manifest.resources,
      idPrefixes: manifest.idPrefixes,
      capabilities: { movies: manifest.types.includes("movie"), series: manifest.types.includes("series"), search: true, directFiles: true, playlists: true, metadata: true }
    }
  };
}
function buildCatalogUrl(baseUrl, catalog) {
  const parsedBaseUrl = normalizeUrl(baseUrl);
  if (!parsedBaseUrl) return null;
  const catalogType = catalog?.type || "movie";
  const catalogId = catalog?.id || "top";
  const catalogUrl = new URL(`/catalog/${catalogType}/${catalogId}.json`, parsedBaseUrl.origin);
  return guardUrl(catalogUrl.href, { allowTorrents: false });
}
function adaptResponse(url, response, source, identity) {
  const sourceKind = detectSourceKind(url, response);
  let adapted = { candidates: [], links: [], metadata: {} };
  if (sourceKind === SOURCE_ADAPTER_KINDS.HTML_PAGE) adapted = parseHtmlPage(response.body, response.finalUrl || url, source);
  else if (sourceKind === SOURCE_ADAPTER_KINDS.JSON_API) adapted = parseJsonApi(response.body, response.finalUrl || url, source);
  else if (sourceKind === SOURCE_ADAPTER_KINDS.ADDON_MANIFEST) adapted = parseAddonManifest(response.body, response.finalUrl || url, source);
  const identityContext = createIdentityContext(identity);
  const scoredCandidates = [];
  for (const candidate of adapted.candidates) {
    const scored = scoreCandidate(candidate, identityContext);
    if (scored.accepted) scoredCandidates.push(scored.candidate);
  }
  return { sourceKind, candidates: scoredCandidates, links: adapted.links, metadata: adapted.metadata };
}
/**
 * clampInteger(value, minimum, maximum[, fallback])
 *
 * Every call site in this resolver passes a fourth "default" argument, but the
 * function only ever declared three — so an omitted option returned `minimum`
 * instead of the intended default. That is how a resolver asked for its default
 * 40 results ran with maximumResults=1 and maximumCandidates=1: one result cap,
 * one candidate, and a search that effectively could not find anything.
 */
function clampInteger(value, minimum, maximum, fallback) {
  const parsedValue = Number(value);
  if (!Number.isFinite(parsedValue)) {
    return fallback === undefined ? minimum : fallback;
  }
  return Math.max(minimum, Math.min(maximum, Math.floor(parsedValue)));
}
module.exports = {
  SOURCE_ADAPTER_KINDS, detectSourceKind, createGlobalSourceRegistry, normalizeSourceCandidate,
  parseHtmlPage, parseJsonApi, parseAddonManifest, adaptResponse
};
