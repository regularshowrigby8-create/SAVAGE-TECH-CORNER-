"use strict";
const { normalizeUrl, guardUrl, getHostname } = require("./policy");
const ERROR_CODES = {
  INVALID_IDENTITY: "invalid-identity", MISSING_IDENTITY: "missing-identity", INVALID_URL: "invalid-url",
  BLOCKED_URL: "blocked-url", RESPONSE_TOO_LARGE: "response-too-large", INVALID_RESPONSE: "invalid-response",
  RATE_LIMITED: "rate-limited", CIRCUIT_OPEN: "circuit-open", MEMORY_LIMIT: "memory-limit",
  ENDPOINT_UNAVAILABLE: "endpoint-unavailable", RESOLUTION_FAILED: "resolution-failed"
};
const DEFAULT_MAXIMUM_CACHE_ENTRIES = 500;
const DEFAULT_MAXIMUM_MEMORY_BYTES = 50 * 1000 * 1000;
const DEFAULT_CIRCUIT_FAILURE_THRESHOLD = 5;
const DEFAULT_CIRCUIT_COOLDOWN_MS = 5 * 60 * 1000;
const DEFAULT_MINIMUM_CONCURRENCY = 2;
const DEFAULT_MAXIMUM_CONCURRENCY = 12;
function createResolverHardening(options) {
  const config = options || {};
  const maximumCacheEntries = clampInteger(config.maximumCacheEntries, 10, 5000, DEFAULT_MAXIMUM_CACHE_ENTRIES);
  const maximumMemoryBytes = clampInteger(config.maximumMemoryBytes, 1000000, 500000000, DEFAULT_MAXIMUM_MEMORY_BYTES);
  const circuitFailureThreshold = clampInteger(config.circuitFailureThreshold, 1, 50, DEFAULT_CIRCUIT_FAILURE_THRESHOLD);
  const circuitCooldownMs = clampInteger(config.circuitCooldownMs, 1000, 3600000, DEFAULT_CIRCUIT_COOLDOWN_MS);
  const minimumConcurrency = clampInteger(config.minimumConcurrency, 1, 16, DEFAULT_MINIMUM_CONCURRENCY);
  const maximumConcurrency = clampInteger(config.maximumConcurrency, 1, 64, DEFAULT_MAXIMUM_CONCURRENCY);
  const cache = new Map();
  const hostCircuits = new Map();
  const urlAllowlist = new Set(config.urlAllowlist || []);
  const urlDenylist = new Set(config.urlDenylist || []);
  let estimatedMemoryBytes = 0;
  return {
    validateIdentity, validateUrl, validateResponse, isUrlAllowed, isHostAvailable, recordHostSuccess, recordHostFailure,
    getCachedResult, cacheResult, evictCache, getAdaptiveConcurrency, recordMemoryUse, getMemoryUsage, getStatistics, clear
  };
  function validateIdentity(identity) {
    const normalizedIdentity = identity || {};
    const title = sanitizeText(normalizedIdentity.title);
    const imdbId = sanitizeText(normalizedIdentity.imdbId);
    const tmdbId = sanitizeText(normalizedIdentity.tmdbId);
    if (!title && !imdbId && !tmdbId) {
      return { valid: false, errorCode: ERROR_CODES.MISSING_IDENTITY, message: "Provide a title, IMDb ID, or TMDB ID." };
    }
    return {
      valid: true,
      identity: {
        title, originalTitle: sanitizeText(normalizedIdentity.originalTitle), imdbId, tmdbId,
        year: sanitizeYear(normalizedIdentity.year), type: normalizeType(normalizedIdentity.type),
        season: sanitizeInteger(normalizedIdentity.season, 1, 1000), episode: sanitizeInteger(normalizedIdentity.episode, 1, 10000),
        aliases: sanitizeAliases(normalizedIdentity.aliases)
      }
    };
  }
  function validateUrl(input) {
    const parsedUrl = guardUrl(input, { allowTorrents: false });
    if (!parsedUrl) return { valid: false, errorCode: ERROR_CODES.INVALID_URL, message: "URL is invalid or unsupported." };
    if (!isUrlAllowed(parsedUrl.href)) return { valid: false, errorCode: ERROR_CODES.BLOCKED_URL, message: "URL is blocked by policy." };
    return { valid: true, url: parsedUrl.href, hostname: getHostname(parsedUrl) };
  }
  function isUrlAllowed(url) {
    const normalizedUrl = String(url || "").toLowerCase();
    if (!normalizedUrl) return false;
    if (urlDenylist.size > 0 && [...urlDenylist].some((deniedUrl) => normalizedUrl.includes(String(deniedUrl).toLowerCase()))) return false;
    if (urlAllowlist.size > 0 && ![...urlAllowlist].some((allowedUrl) => normalizedUrl.includes(String(allowedUrl).toLowerCase()))) return false;
    return true;
  }
  function validateResponse(response, options) {
    const config = options || {};
    const contentLength = Number(response?.contentLength || 0);
    const maximumBytes = clampInteger(config.maximumBytes, 1000, 5000000000, 5000000000);
    if (contentLength > maximumBytes) return { valid: false, errorCode: ERROR_CODES.RESPONSE_TOO_LARGE, message: "Response exceeds the allowed size." };
    if (!response || typeof response !== "object" || response.status < 200 || response.status >= 400) {
      return { valid: false, errorCode: ERROR_CODES.INVALID_RESPONSE, message: "Response is invalid." };
    }
    return { valid: true, contentType: response.contentType, contentLength };
  }
  function isHostAvailable(url) {
    const hostname = getHostname(url);
    if (!hostname) return false;
    const circuit = hostCircuits.get(hostname);
    if (!circuit) return true;
    if (circuit.state === "open") {
      if (Date.now() >= circuit.reopenAt) { circuit.state = "half-open"; return true; }
      return false;
    }
    return true;
  }
  function recordHostSuccess(url) {
    const hostname = getHostname(url);
    if (!hostname) return;
    const circuit = hostCircuits.get(hostname);
    if (circuit) { circuit.failures = 0; circuit.state = "closed"; circuit.reopenAt = 0; }
  }
  function recordHostFailure(url) {
    const hostname = getHostname(url);
    if (!hostname) return;
    const circuit = hostCircuits.get(hostname) || { failures: 0, state: "closed", reopenAt: 0 };
    circuit.failures += 1;
    if (circuit.failures >= circuitFailureThreshold) { circuit.state = "open"; circuit.reopenAt = Date.now() + circuitCooldownMs; }
    hostCircuits.set(hostname, circuit);
  }
  function getCachedResult(cacheKey) {
    const cached = cache.get(cacheKey);
    if (!cached) return null;
    if (Date.now() > cached.expiresAt) { cache.delete(cacheKey); estimatedMemoryBytes -= cached.sizeBytes || 0; return null; }
    return cached.payload;
  }
  function cacheResult(cacheKey, payload, options) {
    const config = options || {};
    const ttlMs = clampInteger(config.ttlMs, 1000, 3600000, 600000);
    const serializedPayload = JSON.stringify(payload || {});
    const sizeBytes = serializedPayload.length;
    if (estimatedMemoryBytes + sizeBytes > maximumMemoryBytes) evictCache(sizeBytes);
    if (cache.size >= maximumCacheEntries) evictCache(sizeBytes);
    cache.set(cacheKey, { payload, expiresAt: Date.now() + ttlMs, sizeBytes });
    estimatedMemoryBytes += sizeBytes;
  }
  function evictCache(requiredBytes) {
    const keys = [...cache.keys()];
    for (const key of keys) {
      const cached = cache.get(key);
      if (!cached) continue;
      estimatedMemoryBytes -= cached.sizeBytes || 0;
      cache.delete(key);
      if (requiredBytes === undefined || estimatedMemoryBytes + requiredBytes <= maximumMemoryBytes) break;
    }
  }
  function getAdaptiveConcurrency(recentFailureRate) {
    const failureRate = clampNumber(recentFailureRate, 0, 1);
    if (failureRate > 0.5) return minimumConcurrency;
    if (failureRate > 0.25) return Math.max(minimumConcurrency, Math.floor(maximumConcurrency / 2));
    return maximumConcurrency;
  }
  function recordMemoryUse(bytes) {
    const sizeBytes = Number(bytes || 0);
    if (sizeBytes > 0) estimatedMemoryBytes += sizeBytes;
  }
  function getMemoryUsage() {
    return { estimatedMemoryBytes, maximumMemoryBytes, cacheEntries: cache.size, maximumCacheEntries };
  }
  function getStatistics() {
    return {
      cacheEntries: cache.size, maximumCacheEntries, estimatedMemoryBytes, maximumMemoryBytes,
      minimumConcurrency, maximumConcurrency,
      openCircuits: [...hostCircuits.values()].filter((c) => c.state === "open").length,
      halfOpenCircuits: [...hostCircuits.values()].filter((c) => c.state === "half-open").length
    };
  }
  function clear() { cache.clear(); hostCircuits.clear(); estimatedMemoryBytes = 0; }
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
function clampInteger(value, minimum, maximum) {
  const parsedValue = Number(value);
  if (!Number.isFinite(parsedValue)) return minimum;
  return Math.max(minimum, Math.min(maximum, Math.floor(parsedValue)));
}
function clampNumber(value, minimum, maximum) {
  const parsedValue = Number(value);
  if (!Number.isFinite(parsedValue)) return minimum;
  return Math.max(minimum, Math.min(maximum, parsedValue));
}
module.exports = {
  createResolverHardening, ERROR_CODES, DEFAULT_MAXIMUM_CACHE_ENTRIES, DEFAULT_MAXIMUM_MEMORY_BYTES,
  DEFAULT_CIRCUIT_FAILURE_THRESHOLD, DEFAULT_CIRCUIT_COOLDOWN_MS, DEFAULT_MINIMUM_CONCURRENCY, DEFAULT_MAXIMUM_CONCURRENCY
};
