"use strict";
const { URL, URLSearchParams } = require("node:url");
const {
  SOURCE_KINDS, normalizeUrl, guardUrl, isDirectMediaUrl, isPlaylistUrl, isManifestUrl, createCrawlPolicy
} = require("./policy");
const { createGlobalFrontier } = require("./frontier");
const { createIdentityContext, scoreCandidate } = require("./identity");
const { createGlobalSourceRegistry, normalizeSourceCandidate } = require("./adapters");
const DEFAULT_EXTRACTION_ENDPOINT = "/api/extract";
const DEFAULT_MAXIMUM_RESULTS = 24;
const DEFAULT_MAXIMUM_CANDIDATES = 48;
const DEFAULT_EXTRACTION_TIMEOUT_MS = 12000;
const DEFAULT_SEARCH_PATHS = ["/search?q={query}","/search?query={query}","/?s={query}","/search/{query}","/search/{query}/"];
function createGlobalResolver(options) {
  const config = options || {};
  const policy = config.policy || createCrawlPolicy({
    globalConcurrency: config.globalConcurrency, hostConcurrency: config.hostConcurrency,
    hostRequestsPerMinute: config.hostRequestsPerMinute, requestTimeoutMs: config.requestTimeoutMs, retryAttempts: config.retryAttempts
  });
  const sourceRegistry = config.sourceRegistry || createGlobalSourceRegistry();
  const extractionEndpoint = config.extractionEndpoint || DEFAULT_EXTRACTION_ENDPOINT;
  const extractionOrigin = config.extractionOrigin || "";
  const maximumResults = clampInteger(config.maximumResults, 1, 100, DEFAULT_MAXIMUM_RESULTS);
  const maximumCandidates = clampInteger(config.maximumCandidates, 1, 200, DEFAULT_MAXIMUM_CANDIDATES);
  const extractionTimeoutMs = clampInteger(config.extractionTimeoutMs, 2000, 30000, DEFAULT_EXTRACTION_TIMEOUT_MS);
  const searchPaths = Array.isArray(config.searchPaths) ? config.searchPaths : DEFAULT_SEARCH_PATHS;
  return { resolveTitle, addSource, getSources, getStatistics, clear };
  function addSource(source) { return sourceRegistry.addSource(source); }
  function getSources(options) { return sourceRegistry.getSources(options); }
  function clear() { sourceRegistry.clear(); }
  /**
   * The factory advertised getStatistics but never defined it, so every caller
   * that asked for resolver health got "getStatistics is not defined" instead of
   * a number. It reports what the registry and the policy can actually see.
   */
  function getStatistics() {
    const sources = (() => {
      try {
        const list = sourceRegistry.getSources({ includeDisabled: true }) || [];
        return { totalSources: list.length, enabledSources: list.filter((source) => source && source.enabled !== false).length };
      } catch (error) {
        return { totalSources: 0, enabledSources: 0 };
      }
    })();
    let policyStats = {};
    try {
      policyStats = typeof policy.getPolicyStatistics === "function" ? policy.getPolicyStatistics() : {};
    } catch (error) {
      policyStats = {};
    }
    return {
      sources,
      policy: policyStats,
      maximumResults,
      maximumCandidates,
      extractionEndpoint,
      searchPaths: searchPaths.slice()
    };
  }
  async function resolveTitle(identity, resolveOptions) {
    const options = resolveOptions || {};
    const identityContext = createIdentityContext(identity);
    if (!identityContext.identity.title && !identityContext.identity.imdbId && !identityContext.identity.tmdbId) return createEmptyResult("missing-identity");
    const frontier = createGlobalFrontier({
      policy, maximumResults: options.maximumResults || maximumResults, maximumCandidates: options.maximumCandidates || maximumCandidates,
      maximumDepth: options.maximumDepth, maximumLinksPerPage: options.maximumLinksPerPage
    });
    const seeds = buildSeedUrls(identityContext, options);
    for (const seed of seeds) frontier.addSeed(seed);
    const frontierResult = await frontier.run({
      maximumResults: options.maximumResults || maximumResults, maximumCandidates: options.maximumCandidates || maximumCandidates,
      workers: options.workers || policy.globalConcurrency
    });
    const directResults = frontierResult.results;
    const candidateResults = frontierResult.candidates;
    const extractionResults = await extractCandidates(candidateResults, identityContext, options);
    const allResults = [...directResults, ...extractionResults];
    const rankedResults = rankPlaybackResults(allResults, identityContext);
    return {
      success: rankedResults.length > 0, identity: identityContext.identity, results: rankedResults,
      statistics: { ...frontierResult.statistics, directResults: directResults.length, candidateResults: candidateResults.length, extractionResults: extractionResults.length, finalResults: rankedResults.length, sources: sourceRegistry.getStatistics() }
    };
  }
  function buildSeedUrls(identityContext, options) {
    const seeds = [];
    const identity = identityContext.identity;
    const query = buildSearchQuery(identity);
    const sources = sourceRegistry.getSources({ enabledOnly: true });
    for (const source of sources) seeds.push(...buildSourceSeeds(source, query, identity));
    const userSeeds = Array.isArray(options.seedUrls) ? options.seedUrls : [];
    for (const seedUrl of userSeeds) {
      const parsedSeed = guardUrl(seedUrl, { allowTorrents: false });
      if (parsedSeed) seeds.push({ url: parsedSeed.href, depth: 0, priority: 80, reason: "user-seed", identity });
    }
    return deduplicateSeeds(seeds);
  }
  function buildSearchQuery(identity) {
    const title = String(identity.title || "").trim();
    if (!title) return "";
    const year = identity.year ? ` ${identity.year}` : "";
    return `${title}${year}`;
  }
  function buildSourceSeeds(source, query, identity) {
    const seeds = [];
    const parsedSourceUrl = normalizeUrl(source.url);
    if (!parsedSourceUrl) return seeds;
    if (source.kind === "addon-manifest") {
      seeds.push({ url: parsedSourceUrl.href, depth: 0, priority: 90, reason: "addon-manifest", sourceName: source.name, identity });
      return seeds;
    }
    const encodedQuery = encodeURIComponent(query);
    for (const searchPath of searchPaths) {
      if (!searchPath.includes("{query}")) continue;
      const searchUrl = new URL(searchPath.replace("{query}", encodedQuery), parsedSourceUrl.origin);
      const guardedUrl = guardUrl(searchUrl.href, { allowTorrents: false });
      if (guardedUrl) seeds.push({ url: guardedUrl.href, depth: 0, priority: source.priority || 50, reason: "source-search", sourceName: source.name, identity });
    }
    return seeds;
  }
  function deduplicateSeeds(seeds) {
    const seen = new Set();
    const uniqueSeeds = [];
    for (const seed of seeds) {
      const key = String(seed.url);
      if (seen.has(key)) continue;
      seen.add(key);
      uniqueSeeds.push(seed);
    }
    return uniqueSeeds;
  }
  async function extractCandidates(candidates, identityContext, options) {
    const extractionResults = [];
    const limitedCandidates = candidates.slice(0, maximumCandidates);
    const extractionPromises = [];
    for (const candidate of limitedCandidates) extractionPromises.push(extractSingleCandidate(candidate, identityContext, options));
    const settledResults = await Promise.allSettled(extractionPromises);
    for (const settledResult of settledResults) {
      if (settledResult.status === "fulfilled") extractionResults.push(...settledResult.value);
    }
    return extractionResults;
  }
  async function extractSingleCandidate(candidate, identityContext, options) {
    const candidateUrl = normalizeUrl(candidate.url);
    if (!candidateUrl) return [];
    const extractionUrl = buildExtractionUrl(candidateUrl.href);
    if (!extractionUrl) return [];
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), extractionTimeoutMs);
    try {
      const response = await fetch(extractionUrl, {
        method: "POST", signal: controller.signal,
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({
          url: candidateUrl.href, title: identityContext.identity.title, year: identityContext.identity.year,
          type: identityContext.identity.type, imdbId: identityContext.identity.imdbId, tmdbId: identityContext.identity.tmdbId,
          season: identityContext.identity.season, episode: identityContext.identity.episode
        })
      });
      if (!response.ok) return [];
      const payload = await response.json();
      return normalizeExtractionResponse(payload, candidate, identityContext);
    } catch (error) { return []; }
    finally { clearTimeout(timeout); }
  }
  function buildExtractionUrl(candidateUrl) {
    const extractionBase = extractionOrigin ? normalizeUrl(extractionOrigin) : null;
    if (!extractionBase) return null;
    const extractionUrl = new URL(extractionEndpoint, extractionBase.origin);
    return guardUrl(extractionUrl.href, { allowTorrents: false });
  }
  function normalizeExtractionResponse(payload, candidate, identityContext) {
    const results = [];
    const streams = extractStreamList(payload);
    for (const stream of streams) {
      const streamUrl = stream.url || stream.href || stream.link || stream.source;
      const parsedStreamUrl = guardUrl(streamUrl, { allowTorrents: false });
      if (!parsedStreamUrl) continue;
      const isDirect = isDirectMediaUrl(parsedStreamUrl);
      const isPlaylist = isPlaylistUrl(parsedStreamUrl);
      const isManifest = isManifestUrl(parsedStreamUrl);
      if (!isDirect && !isPlaylist && !isManifest) continue;
      const normalizedCandidate = normalizeSourceCandidate({
        ...candidate, url: parsedStreamUrl.href,
        kind: isPlaylist ? SOURCE_KINDS.HLS_PLAYLIST : isManifest ? SOURCE_KINDS.DASH_MANIFEST : SOURCE_KINDS.DIRECT_FILE,
        title: candidate.title || identityContext.identity.title
      }, { id: candidate.sourceId, name: candidate.sourceName, kind: candidate.sourceKind });
      const scored = scoreCandidate(normalizedCandidate, identityContext);
      if (!scored.accepted) continue;
      results.push({
        id: createStableId(parsedStreamUrl.href), url: parsedStreamUrl.href, sourceId: candidate.sourceId, sourceName: candidate.sourceName, sourceKind: candidate.sourceKind,
        resultKind: isPlaylist ? "playlist" : isManifest ? "manifest" : "direct-media",
        title: identityContext.identity.title, year: identityContext.identity.year, type: identityContext.identity.type,
        season: identityContext.identity.season, episode: identityContext.identity.episode, identityScore: scored.identityScore,
        quality: stream.quality || "", size: stream.size || "", codec: stream.codec || "", language: stream.language || "",
        verified: true, discoveredAt: Date.now()
      });
    }
    return results;
  }
  function extractStreamList(payload) {
    if (!payload) return [];
    if (Array.isArray(payload.streams)) return payload.streams;
    if (Array.isArray(payload.sources)) return payload.sources;
    if (Array.isArray(payload.links)) return payload.links;
    if (Array.isArray(payload)) return payload;
    if (payload.stream && typeof payload.stream === "object") return [payload.stream];
    return [];
  }
  function rankPlaybackResults(results, identityContext) {
    const uniqueResults = new Map();
    for (const result of results) {
      if (!result || !result.url) continue;
      const key = result.url;
      const existingResult = uniqueResults.get(key);
      if (!existingResult || result.identityScore > existingResult.identityScore) uniqueResults.set(key, result);
    }
    const rankedResults = [...uniqueResults.values()];
    rankedResults.sort((a, b) => calculatePlaybackScore(b) - calculatePlaybackScore(a));
    return rankedResults.slice(0, maximumResults);
  }
  function calculatePlaybackScore(result) {
    let score = result.identityScore || 0;
    if (result.resultKind === "direct-media") score += 30;
    else if (result.resultKind === "playlist") score += 26;
    else if (result.resultKind === "manifest") score += 24;
    if (result.verified) score += 10;
    const quality = String(result.quality || "").toLowerCase();
    if (quality.includes("2160")) score += 12;
    else if (quality.includes("1080")) score += 10;
    else if (quality.includes("720")) score += 6;
    return score;
  }
  function createEmptyResult(reason) {
    return {
      success: false, reason, identity: null, results: [],
      statistics: { directResults: 0, candidateResults: 0, extractionResults: 0, finalResults: 0, sources: sourceRegistry.getStatistics() }
    };
  }
}
function createStableId(input) {
  const crypto = require("node:crypto");
  return crypto.createHash("sha256").update(String(input)).digest("hex").slice(0, 32);
}
function clampInteger(value, minimum, maximum) {
  const parsedValue = Number(value);
  if (!Number.isFinite(parsedValue)) return minimum;
  return Math.max(minimum, Math.min(maximum, Math.floor(parsedValue)));
}
module.exports = {
  createGlobalResolver, DEFAULT_EXTRACTION_ENDPOINT, DEFAULT_MAXIMUM_RESULTS,
  DEFAULT_MAXIMUM_CANDIDATES, DEFAULT_EXTRACTION_TIMEOUT_MS, DEFAULT_SEARCH_PATHS
};
