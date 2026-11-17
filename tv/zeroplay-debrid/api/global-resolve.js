"use strict";
const { createGlobalResolver } = require("../lib/global-resolver/resolver");
const DEFAULT_CACHE_TTL_MS = 10 * 60 * 1000;
const DEFAULT_RATE_LIMIT_WINDOW_MS = 60 * 1000;
const DEFAULT_MAXIMUM_REQUESTS_PER_WINDOW = 20;
const DEFAULT_MAXIMUM_RESULTS = 18;
const responseCache = new Map();
const rateLimitEntries = new Map();
/**
 * Vercel + Netlify entry point.
 *
 * The batch shipped this as a bare (req, res) listener that called
 * res.statusCode/res.setHeader directly and never exposed res.status()/res.json().
 * api/extract.js in this project is dual-exported for exactly that reason: Vercel
 * passes a Node response, Netlify passes one with helpers. Written the old way
 * the route simply never answered on Netlify and the client fell back to "global
 * search unavailable" forever.
 */
module.exports = async (req, res) => {
  const result = await handleGlobalResolve(req);
  if (res && typeof res.status === "function" && typeof res.json === "function") {
    res.setHeader("Cache-Control", "no-store");
    return res.status(result.statusCode).json(result.payload);
  }
  return sendJson(res, result.statusCode, result.payload);
};

/** Platform-free core, so it can be unit tested without a server. */
async function handleGlobalResolve(request) {
  const startTime = Date.now();
  try {
    if (request.method !== "POST" && request.method !== "GET") {
      return { statusCode: 405, payload: { success: false, error: "method-not-allowed", message: "Use GET or POST." } };
    }
    const clientKey = getClientKey(request);
    const rateLimit = checkRateLimit(clientKey);
    if (!rateLimit.allowed) {
      return { statusCode: 429, payload: { success: false, error: "rate-limit-exceeded", message: "Too many requests. Try again shortly.", retryAfterMs: rateLimit.retryAfterMs } };
    }
    const identity = await parseIdentity(request);
    if (!identity) {
      return { statusCode: 400, payload: { success: false, error: "invalid-request", message: "Provide a title, IMDb ID, or TMDB ID." } };
    }
    const cacheKey = createCacheKey(identity);
    const cachedResponse = getCachedResponse(cacheKey);
    if (cachedResponse) {
      return { statusCode: 200, payload: { ...cachedResponse, cached: true, rateLimit: buildRateLimitPayload(rateLimit) } };
    }
    const resolver = createGlobalResolver({ maximumResults: DEFAULT_MAXIMUM_RESULTS, requestTimeoutMs: 15000 });
    const resolution = await resolver.resolveTitle(identity, { maximumResults: DEFAULT_MAXIMUM_RESULTS, maximumCandidates: 48, maximumDepth: 2, maximumLinksPerPage: 40 });
    const safeResults = sanitizeResults(resolution.results);
    const payload = {
      success: safeResults.length > 0,
      identity: {
        title: identity.title || "", originalTitle: identity.originalTitle || "", year: identity.year || "", type: identity.type || "",
        imdbId: identity.imdbId || "", tmdbId: identity.tmdbId || "", season: identity.season || null, episode: identity.episode || null
      },
      results: safeResults,
      statistics: {
        resultCount: safeResults.length, directResults: resolution.statistics.directResults || 0,
        candidateResults: resolution.statistics.candidateResults || 0, extractionResults: resolution.statistics.extractionResults || 0,
        sources: resolution.statistics.sources || {}, durationMs: Date.now() - startTime
      },
      cached: false, rateLimit: buildRateLimitPayload(rateLimit)
    };
    if (safeResults.length > 0) cacheResponse(cacheKey, payload);
    return { statusCode: 200, payload };
  } catch (error) {
    return { statusCode: 500, payload: { success: false, error: "global-resolution-failed", message: "Unable to complete global resolution." } };
  }
};
async function parseIdentity(request) {
  let body = {};
  if (request.method === "POST") body = request.body && typeof request.body === "object" ? request.body : {};
  else body = request.query || request.searchParams || {};
  const title = sanitizeText(body.title || body.query || "");
  const originalTitle = sanitizeText(body.originalTitle || body.original_title || "");
  const imdbId = sanitizeText(body.imdbId || body.imdb_id || "");
  const tmdbId = sanitizeText(body.tmdbId || body.tmdb_id || "");
  const year = sanitizeYear(body.year);
  const type = normalizeType(body.type || body.mediaType);
  const season = sanitizeInteger(body.season, 1, 1000);
  const episode = sanitizeInteger(body.episode, 1, 10000);
  const aliases = sanitizeAliases(body.aliases);
  if (!title && !imdbId && !tmdbId) return null;
  return { title, originalTitle, imdbId, tmdbId, year, type, season, episode, aliases };
}
function sanitizeText(input) { return String(input || "").trim().slice(0, 300); }
function sanitizeYear(input) {
  const year = Number(input);
  if (!Number.isFinite(year) || year < 1888 || year > 2100) return "";
  return Math.floor(year);
}
function sanitizeInteger(input, minimum, maximum) {
  const value = Number(input);
  if (!Number.isFinite(value) || value < minimum || value > maximum) return null;
  return Math.floor(value);
}
function sanitizeAliases(input) {
  if (!Array.isArray(input)) return [];
  return input.filter((alias) => typeof alias === "string" && alias.trim().length > 0).slice(0, 10).map((alias) => sanitizeText(alias));
}
function normalizeType(input) {
  const value = String(input || "").toLowerCase().trim();
  if (value === "movie" || value === "film") return "movie";
  if (value === "series" || value === "tv" || value === "show") return "series";
  return "";
}
function sanitizeResults(results) {
  if (!Array.isArray(results)) return [];
  const safeResults = [];
  for (const result of results) {
    const url = sanitizeText(result.url);
    if (!url) continue;
    safeResults.push({
      id: sanitizeText(result.id) || createStableId(url), url, title: sanitizeText(result.title),
      sourceId: sanitizeText(result.sourceId), sourceName: sanitizeText(result.sourceName), sourceKind: sanitizeText(result.sourceKind),
      resultKind: sanitizeText(result.resultKind), quality: sanitizeText(result.quality), size: sanitizeText(result.size),
      codec: sanitizeText(result.codec), language: sanitizeText(result.language), identityScore: sanitizeScore(result.identityScore),
      verified: result.verified === true, readyToUse: result.readyToUse !== false, discoveredAt: sanitizeTimestamp(result.discoveredAt)
    });
  }
  return safeResults.slice(0, DEFAULT_MAXIMUM_RESULTS);
}
function sanitizeScore(input) {
  const score = Number(input);
  if (!Number.isFinite(score) || score < 0) return 0;
  return Math.min(100, Math.round(score));
}
function sanitizeTimestamp(input) {
  const timestamp = Number(input);
  if (!Number.isFinite(timestamp) || timestamp <= 0) return Date.now();
  return Math.floor(timestamp);
}
function getClientKey(request) {
  const forwardedFor = request.headers?.["x-forwarded-for"] || request.headers?.["X-Forwarded-For"] || "";
  const clientIp = String(forwardedFor).split(",")[0].trim() || request.socket?.remoteAddress || "unknown";
  return createStableId(clientIp);
}
function checkRateLimit(clientKey) {
  const now = Date.now();
  const windowStart = now - DEFAULT_RATE_LIMIT_WINDOW_MS;
  const existingEntry = rateLimitEntries.get(clientKey) || { requests: [] };
  const recentRequests = existingEntry.requests.filter((timestamp) => timestamp > windowStart);
  if (recentRequests.length >= DEFAULT_MAXIMUM_REQUESTS_PER_WINDOW) {
    const oldestRequest = recentRequests[0];
    const retryAfterMs = Math.max(0, oldestRequest + DEFAULT_RATE_LIMIT_WINDOW_MS - now);
    return { allowed: false, remaining: 0, retryAfterMs };
  }
  recentRequests.push(now);
  rateLimitEntries.set(clientKey, { requests: recentRequests });
  return { allowed: true, remaining: DEFAULT_MAXIMUM_REQUESTS_PER_WINDOW - recentRequests.length, retryAfterMs: 0 };
}
function buildRateLimitPayload(rateLimit) {
  return { remaining: rateLimit.remaining || 0, retryAfterMs: rateLimit.retryAfterMs || 0 };
}
function createCacheKey(identity) {
  return createStableId(JSON.stringify({
    title: identity.title, originalTitle: identity.originalTitle, imdbId: identity.imdbId, tmdbId: identity.tmdbId,
    year: identity.year, type: identity.type, season: identity.season, episode: identity.episode
  }));
}
function getCachedResponse(cacheKey) {
  const cached = responseCache.get(cacheKey);
  if (!cached) return null;
  if (Date.now() > cached.expiresAt) { responseCache.delete(cacheKey); return null; }
  return cached.payload;
}
function cacheResponse(cacheKey, payload) {
  responseCache.set(cacheKey, { payload, expiresAt: Date.now() + DEFAULT_CACHE_TTL_MS });
  if (responseCache.size > 500) {
    const oldestKey = responseCache.keys().next().value;
    responseCache.delete(oldestKey);
  }
}
function createStableId(input) {
  const crypto = require("node:crypto");
  return crypto.createHash("sha256").update(String(input)).digest("hex").slice(0, 32);
}
function sendJson(response, statusCode, payload) {
  response.statusCode = statusCode;
  response.setHeader("Content-Type", "application/json");
  response.setHeader("Cache-Control", "no-store");
  response.end(JSON.stringify(payload));
}
