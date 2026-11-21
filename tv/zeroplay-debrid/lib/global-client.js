"use strict";
const DEFAULT_GLOBAL_ENDPOINT = "/api/global-resolve";
const DEFAULT_REQUEST_TIMEOUT_MS = 15000;
const DEFAULT_GLOBAL_DELAY_MS = 200;
function createGlobalClient(options) {
  const config = options || {};
  const globalEndpoint = config.globalEndpoint || DEFAULT_GLOBAL_ENDPOINT;
  const requestTimeoutMs = clampInteger(config.requestTimeoutMs, 2000, 30000, DEFAULT_REQUEST_TIMEOUT_MS);
  const globalDelayMs = clampInteger(config.globalDelayMs, 0, 5000, DEFAULT_GLOBAL_DELAY_MS);
  let activeSearchToken = null;
  let activeAbortController = null;
  return { search, cancelActiveSearch, resolveTitle, getEndpoint };
  function getEndpoint() { return globalEndpoint; }
  function cancelActiveSearch() {
    if (activeAbortController) { activeAbortController.abort(); activeAbortController = null; }
    activeSearchToken = null;
  }
  async function search(searchRequest, callbacks) {
    const request = normalizeSearchRequest(searchRequest);
    const callbackConfig = callbacks || {};
    cancelActiveSearch();
    const searchToken = createSearchToken();
    activeSearchToken = searchToken;
    activeAbortController = new AbortController();
    const abortSignal = activeAbortController.signal;
    const localResults = [];
    const globalResults = [];
    if (typeof callbackConfig.onSearchStart === "function") callbackConfig.onSearchStart({ request, searchToken });
    const localPromise = runLocalSearch(request, callbackConfig, { searchToken, abortSignal, onResults: handleLocalResults });
    const globalPromise = runGlobalSearch(request, callbackConfig, { searchToken, abortSignal, onResults: handleGlobalResults, onProgress: handleGlobalProgress, onError: handleGlobalError });
    await Promise.allSettled([localPromise, globalPromise]);
    const finalResults = mergeResults(localResults, globalResults);
    if (typeof callbackConfig.onSearchComplete === "function") {
      callbackConfig.onSearchComplete({ request, searchToken, results: finalResults, localResultCount: localResults.length, globalResultCount: globalResults.length });
    }
    return { results: finalResults, localResults: localResults.slice(), globalResults: globalResults.slice() };
    function handleLocalResults(results) {
      if (!isCurrentSearch(searchToken)) return;
      localResults.length = 0;
      localResults.push(...normalizeResultList(results, "local"));
      emitResults(callbackConfig, mergeResults(localResults, globalResults), "local");
    }
    function handleGlobalResults(results) {
      if (!isCurrentSearch(searchToken)) return;
      globalResults.length = 0;
      globalResults.push(...normalizeResultList(results, "global"));
      emitResults(callbackConfig, mergeResults(localResults, globalResults), "global");
    }
    function handleGlobalProgress(progress) {
      if (typeof callbackConfig.onGlobalProgress === "function") callbackConfig.onGlobalProgress({ request, searchToken, progress });
    }
    function handleGlobalError(error) {
      if (typeof callbackConfig.onSearchError === "function") callbackConfig.onSearchError({ request, searchToken, source: "global", error });
    }
  }
  async function runLocalSearch(request, callbacks, context) {
    if (typeof callbacks.onLocalSearch !== "function") return [];
    const results = await callbacks.onLocalSearch(request, context);
    if (context.abortSignal.aborted || !isCurrentSearch(context.searchToken)) return [];
    const normalizedResults = normalizeResultList(results, "local");
    if (typeof context.onResults === "function") context.onResults(normalizedResults);
    return normalizedResults;
  }
  async function runGlobalSearch(request, callbacks, context) {
    if (globalDelayMs > 0) await delay(globalDelayMs);
    if (context.abortSignal.aborted || !isCurrentSearch(context.searchToken)) return [];
    if (typeof callbacks.onGlobalSearch !== "function") return [];
    const results = await callbacks.onGlobalSearch(request, context);
    if (context.abortSignal.aborted || !isCurrentSearch(context.searchToken)) return [];
    const normalizedResults = normalizeResultList(results, "global");
    if (typeof context.onResults === "function") context.onResults(normalizedResults);
    return normalizedResults;
  }
  async function resolveTitle(identity) {
    const normalizedIdentity = normalizeSearchRequest(identity);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), requestTimeoutMs);
    try {
      const response = await fetch(globalEndpoint, {
        method: "POST", signal: controller.signal,
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({
          title: normalizedIdentity.title, originalTitle: normalizedIdentity.originalTitle, year: normalizedIdentity.year,
          type: normalizedIdentity.type, imdbId: normalizedIdentity.imdbId, tmdbId: normalizedIdentity.tmdbId,
          season: normalizedIdentity.season, episode: normalizedIdentity.episode, aliases: normalizedIdentity.aliases
        })
      });
      if (!response.ok) return { success: false, reason: `http-${response.status}`, results: [] };
      const payload = await response.json();
      return { success: payload.success === true, results: normalizeResultList(payload.results, "global"), statistics: payload.statistics || {}, cached: payload.cached === true, rateLimit: payload.rateLimit || {} };
    } catch (error) {
      return { success: false, reason: error.name === "AbortError" ? "request-timeout" : "request-failed", results: [] };
    } finally { clearTimeout(timeout); }
  }
  function mergeResults(localResults, globalResults) {
    const mergedResults = [];
    const seenUrls = new Set();
    const localRanked = rankResults(localResults);
    const globalRanked = rankResults(globalResults);
    for (const result of [...localRanked, ...globalRanked]) {
      const urlKey = String(result.url || "");
      if (urlKey && seenUrls.has(urlKey)) continue;
      if (urlKey) seenUrls.add(urlKey);
      mergedResults.push(result);
    }
    return mergedResults;
  }
  function rankResults(results) {
    if (!Array.isArray(results)) return [];
    return results.filter(Boolean).sort((a, b) => calculateResultScore(b) - calculateResultScore(a));
  }
  function calculateResultScore(result) {
    let score = result.relevanceScore || 0;
    if (result.origin === "local") score += 12;
    if (result.readyToUse) score += 18;
    if (result.resultKind === "direct-media") score += 14;
    else if (result.resultKind === "playlist") score += 12;
    else if (result.resultKind === "manifest") score += 10;
    if (result.verified) score += 8;
    return score;
  }
  function normalizeSearchRequest(request) {
    const input = request || {};
    return {
      query: input.query || input.title || "", title: input.title || input.query || "",
      originalTitle: input.originalTitle || input.original_title || "", year: input.year || "",
      type: normalizeMediaType(input.type || input.mediaType),
      imdbId: input.imdbId || input.imdb_id || "", tmdbId: input.tmdbId || input.tmdb_id || "",
      season: input.season !== undefined && input.season !== null ? Number(input.season) : null,
      episode: input.episode !== undefined && input.episode !== null ? Number(input.episode) : null,
      aliases: Array.isArray(input.aliases) ? input.aliases : []
    };
  }
  function normalizeMediaType(input) {
    const value = String(input || "").toLowerCase().trim();
    if (value === "movie" || value === "film") return "movie";
    if (value === "series" || value === "tv" || value === "show") return "series";
    return "";
  }
  function normalizeResultList(results, origin) {
    if (!Array.isArray(results)) return [];
    return results.map((result) => normalizeResult(result, origin)).filter(Boolean);
  }
  function normalizeResult(result, origin) {
    if (!result) return null;
    const url = result.url || result.href || result.link || "";
    if (!url) return null;
    return {
      id: result.id || createStableId(url), url, title: result.title || result.name || "",
      originalTitle: result.originalTitle || result.original_title || "", year: result.year || "", type: result.type || "",
      season: result.season || null, episode: result.episode || null,
      imdbId: result.imdbId || result.imdb_id || "", tmdbId: result.tmdbId || result.tmdb_id || "",
      sourceId: result.sourceId || "", sourceName: result.sourceName || "", sourceKind: result.sourceKind || "",
      resultKind: result.resultKind || result.kind || "direct-media", origin: result.origin || origin,
      quality: result.quality || "", size: result.size || "", codec: result.codec || "", language: result.language || "",
      verified: result.verified === true, readyToUse: result.readyToUse !== false,
      relevanceScore: result.identityScore || result.relevanceScore || 0, discoveredAt: result.discoveredAt || Date.now()
    };
  }
  function isCurrentSearch(searchToken) { return activeSearchToken === searchToken; }
  function emitResults(callbacks, results, source) {
    if (typeof callbacks.onResults === "function") callbacks.onResults({ results, source });
  }
}
function createSearchToken() { return `search-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`; }
function createStableId(input) {
  const crypto = require("node:crypto");
  return crypto.createHash("sha256").update(String(input)).digest("hex").slice(0, 32);
}
function delay(ms) { return new Promise((resolve) => setTimeout(resolve, ms)); }
function clampInteger(value, minimum, maximum) {
  const parsedValue = Number(value);
  if (!Number.isFinite(parsedValue)) return minimum;
  return Math.max(minimum, Math.min(maximum, Math.floor(parsedValue)));
}
module.exports = { createGlobalClient, DEFAULT_GLOBAL_ENDPOINT, DEFAULT_REQUEST_TIMEOUT_MS, DEFAULT_GLOBAL_DELAY_MS };
