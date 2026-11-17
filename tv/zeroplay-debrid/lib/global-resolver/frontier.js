"use strict";
const { URL } = require("node:url");
const {
  SOURCE_KINDS, normalizeUrl, guardUrl, classifyUrl, getHostname,
  isDirectMediaUrl, isPlaylistUrl, isManifestUrl, createCrawlPolicy
} = require("./policy");
const DEFAULT_MAXIMUM_FRONTIER_ITEMS = 240;
const DEFAULT_MAXIMUM_RESULTS = 48;
const DEFAULT_MAXIMUM_DEPTH = 2;
const DEFAULT_MAXIMUM_LINKS_PER_PAGE = 40;
const DEFAULT_MAXIMUM_CANDIDATES = 24;
const PRIORITY_WEIGHTS = {
  WATCH_PAGE: 100, DETAIL_PAGE: 82, API_ENDPOINT: 76, DIRECT_FILE: 96,
  HLS_PLAYLIST: 94, DASH_MANIFEST: 92, ADDON_MANIFEST: 70, SEARCH_PAGE: 60,
  WEB_PAGE: 34, UNKNOWN: 0
};
const RETRYABLE_STATUS_CODES = new Set([408,425,429,500,502,503,504,522,524]);
function createGlobalFrontier(options) {
  const config = options || {};
  const policy = config.policy || createCrawlPolicy({
    globalConcurrency: config.globalConcurrency,
    hostConcurrency: config.hostConcurrency,
    hostRequestsPerMinute: config.hostRequestsPerMinute,
    requestTimeoutMs: config.requestTimeoutMs,
    retryAttempts: config.retryAttempts
  });
  const maximumFrontierItems = clampInteger(config.maximumFrontierItems, 10, 1000, DEFAULT_MAXIMUM_FRONTIER_ITEMS);
  const maximumResults = clampInteger(config.maximumResults, 1, 200, DEFAULT_MAXIMUM_RESULTS);
  const maximumDepth = clampInteger(config.maximumDepth, 0, 4, DEFAULT_MAXIMUM_DEPTH);
  const maximumLinksPerPage = clampInteger(config.maximumLinksPerPage, 1, 200, DEFAULT_MAXIMUM_LINKS_PER_PAGE);
  const maximumCandidates = clampInteger(config.maximumCandidates, 1, 100, DEFAULT_MAXIMUM_CANDIDATES);
  const frontier = [];
  const frontierKeys = new Set();
  const completedUrls = new Set();
  const failedUrls = new Map();
  const results = [];
  const candidates = [];
  let activeWorkers = 0;
  let frontierClosed = false;
  return {
    policy, addSeed, addUrl, addLinks, run, getResults, getCandidates, getStatistics, clear
  };
  function addSeed(seed) {
    if (!seed) return null;
    const normalizedSeed = typeof seed === "string" ? { url: seed, depth: 0, priority: 50, reason: "manual-seed" } : seed;
    return addUrl({
      url: normalizedSeed.url, depth: normalizedSeed.depth || 0, priority: normalizedSeed.priority || 50,
      reason: normalizedSeed.reason || "manual-seed", sourceName: normalizedSeed.sourceName || null, identity: normalizedSeed.identity || null
    });
  }
  function addUrl(input) {
    if (!input || frontierClosed) return null;
    const parsedUrl = guardUrl(input.url, { allowTorrents: false });
    if (!parsedUrl) return null;
    const urlKey = parsedUrl.href;
    if (frontierKeys.has(urlKey) || completedUrls.has(urlKey) || frontier.length >= maximumFrontierItems) return null;
    const depth = clampInteger(input.depth || 0, 0, maximumDepth);
    const kind = classifyUrl(parsedUrl);
    const priority = calculatePriority({ kind, requestedPriority: input.priority || 0, identity: input.identity, url: parsedUrl, depth });
    const item = {
      id: createStableId(urlKey), url: parsedUrl.href, parsedUrl, hostname: getHostname(parsedUrl), kind, depth, priority,
      reason: input.reason || "discovered-link", sourceName: input.sourceName || null, identity: input.identity || null, addedAt: Date.now()
    };
    frontier.push(item);
    frontierKeys.add(urlKey);
    sortFrontier();
    return item;
  }
  function addLinks(links, context) {
    if (!Array.isArray(links)) return [];
    const config = context || {};
    const addedLinks = [];
    const limitedLinks = links.slice(0, maximumLinksPerPage);
    for (const link of limitedLinks) {
      const absoluteUrl = resolveLink(link, config.baseUrl);
      if (!absoluteUrl) continue;
      const addedItem = addUrl({
        url: absoluteUrl, depth: (config.depth || 0) + 1, priority: calculateLinkPriority(absoluteUrl, config),
        reason: config.reason || "page-link", sourceName: config.sourceName || null, identity: config.identity || null
      });
      if (addedItem) addedLinks.push(addedItem);
    }
    return addedLinks;
  }
  function resolveLink(link, baseUrl) {
    if (!link) return null;
    const rawLink = typeof link === "string" ? link : link.url || link.href || link.link;
    if (!rawLink) return null;
    const parsedBaseUrl = normalizeUrl(baseUrl);
    if (!parsedBaseUrl) return null;
    try {
      const resolvedUrl = new URL(rawLink, parsedBaseUrl);
      return guardUrl(resolvedUrl.href, { allowTorrents: false });
    } catch (error) { return null; }
  }
  function calculateLinkPriority(url, context) {
    const kind = classifyUrl(url);
    let priority = PRIORITY_WEIGHTS[kind] || 0;
    if (context.priority) priority += Number(context.priority);
    if (context.depth > 0) priority -= context.depth * 8;
    return priority;
  }
  function calculatePriority(input) {
    const kindWeight = PRIORITY_WEIGHTS[input.kind] || 0;
    const requestedPriority = Number(input.requestedPriority || 0);
    const identityBonus = calculateIdentityBonus(input.url, input.identity);
    const depthPenalty = input.depth * 6;
    return Math.max(0, kindWeight + requestedPriority + identityBonus - depthPenalty);
  }
  function calculateIdentityBonus(url, identity) {
    if (!url || !identity) return 0;
    let bonus = 0;
    const urlText = String(url.href || url).toLowerCase();
    const title = String(identity.title || "").toLowerCase().trim();
    if (title && urlText.includes(title)) bonus += 18;
    const imdbId = String(identity.imdbId || "").toLowerCase().trim();
    if (imdbId && urlText.includes(imdbId)) bonus += 26;
    const tmdbId = String(identity.tmdbId || "").trim();
    if (tmdbId && urlText.includes(tmdbId)) bonus += 24;
    const year = String(identity.year || "");
    if (year && urlText.includes(year)) bonus += 8;
    const season = String(identity.season || "");
    const episode = String(identity.episode || "");
    if (season && episode && (urlText.includes(`s${season}e${episode}`) || urlText.includes(`season-${season}`) || urlText.includes(`episode-${episode}`))) bonus += 22;
    return bonus;
  }
  function sortFrontier() { frontier.sort((a, b) => b.priority - a.priority); }
  async function run(requestContext) {
    const context = requestContext || {};
    const maximumResultsForRun = clampInteger(context.maximumResults || maximumResults, 1, maximumResults);
    const maximumCandidatesForRun = clampInteger(context.maximumCandidates || maximumCandidates, 1, maximumCandidates);
    const workers = clampInteger(context.workers || policy.globalConcurrency, 1, policy.globalConcurrency);
    const workerPromises = [];
    for (let index = 0; index < workers; index += 1) {
      workerPromises.push(runWorker({ maximumResults: maximumResultsForRun, maximumCandidates: maximumCandidatesForRun }));
    }
    await Promise.all(workerPromises);
    return { results: getResults(), candidates: getCandidates(), statistics: getStatistics() };
  }
  async function runWorker(limits) {
    while (frontier.length > 0 && results.length < limits.maximumResults && candidates.length < limits.maximumCandidates && !frontierClosed) {
      const item = frontier.shift();
      if (!item) break;
      frontierKeys.delete(item.url);
      if (completedUrls.has(item.url)) continue;
      const reservation = policy.reserveRequest(item.url);
      if (!reservation) {
        failedUrls.set(item.url, { reason: "rate-limited-or-backoff", attempts: 0 });
        continue;
      }
      activeWorkers += 1;
      try {
        const response = await fetchUrl(item, reservation);
        completedUrls.add(item.url);
        processResponse(item, response, limits);
        policy.recordSuccess(item.url);
      } catch (error) {
        const failure = policy.recordFailure(item.url, { statusCode: error.statusCode || null, errorMessage: error.message || String(error) });
        const attempts = (failedUrls.get(item.url)?.attempts || 0) + 1;
        failedUrls.set(item.url, { reason: error.message || "request-failed", attempts, backoffDelay: failure?.backoffDelay || 0 });
        const shouldRetry = attempts <= policy.retryAttempts && (!error.statusCode || RETRYABLE_STATUS_CODES.has(error.statusCode));
        if (shouldRetry) {
          setTimeout(() => {
            addUrl({ url: item.url, depth: item.depth, priority: Math.max(1, item.priority - 10), reason: "retry", sourceName: item.sourceName, identity: item.identity });
          }, failure?.backoffDelay || 1000);
        }
      } finally {
        policy.releaseRequest(reservation);
        activeWorkers -= 1;
      }
    }
  }
  async function fetchUrl(item, reservation) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), policy.requestTimeoutMs);
    try {
      const response = await fetch(item.url, {
        method: "GET", signal: controller.signal, redirect: "follow",
        headers: { Accept: "text/html,application/json,application/vnd.stremio+json,*/*", "User-Agent": "ZeroPlayTV/1.0" }
      });
      if (!response.ok) {
        const error = new Error(`HTTP ${response.status}`);
        error.statusCode = response.status;
        throw error;
      }
      const contentType = String(response.headers.get("content-type") || "").toLowerCase();
      const contentLength = Number(response.headers.get("content-length") || 0);
      if (contentLength > 0 && contentLength > policy.maximumFileSizeBytes) {
        const error = new Error("Response exceeds maximum allowed size");
        error.statusCode = 413;
        throw error;
      }
      let body = null;
      if (contentType.includes("application/json") || item.url.endsWith(".json")) body = await response.json();
      else if (!isDirectMediaUrl(item.url) && !isPlaylistUrl(item.url) && !isManifestUrl(item.url)) body = await response.text();
      return { status: response.status, contentType, contentLength, finalUrl: response.url || item.url, body, headers: response.headers };
    } finally { clearTimeout(timeout); }
  }
  function processResponse(item, response, limits) {
    const result = {
      id: createStableId(item.url), url: item.url, finalUrl: response.finalUrl, kind: item.kind, sourceName: item.sourceName,
      identity: item.identity, status: response.status, contentType: response.contentType, discoveredAt: Date.now(), verified: false, score: item.priority
    };
    if (item.kind === SOURCE_KINDS.DIRECT_FILE || isDirectMediaUrl(item.url)) { result.resultKind = "direct-media"; result.readyToUse = true; results.push(result); return; }
    if (item.kind === SOURCE_KINDS.HLS_PLAYLIST || isPlaylistUrl(item.url)) { result.resultKind = "playlist"; result.readyToUse = true; results.push(result); return; }
    if (item.kind === SOURCE_KINDS.DASH_MANIFEST || isManifestUrl(item.url)) { result.resultKind = "manifest"; result.readyToUse = true; results.push(result); return; }
    if (item.kind === SOURCE_KINDS.WATCH_PAGE || item.kind === SOURCE_KINDS.DETAIL_PAGE || item.kind === SOURCE_KINDS.API_ENDPOINT) {
      result.resultKind = "candidate";
      result.readyToUse = false;
      candidates.push(result);
    }
    if (response.body && typeof response.body === "string" && item.depth < maximumDepth) {
      const extractedLinks = extractLinks(response.body, response.finalUrl);
      addLinks(extractedLinks, { baseUrl: response.finalUrl, depth: item.depth, priority: item.priority, reason: `${item.kind}-links`, sourceName: item.sourceName, identity: item.identity });
    }
  }
  function extractLinks(html, baseUrl) {
    if (!html || typeof html !== "string") return [];
    const links = [];
    const patterns = [
      /<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>/gi,
      /<iframe\b[^>]*src\s*=\s*["']([^"']+)["'][^>]*>/gi,
      /<source\b[^>]*src\s*=\s*["']([^"']+)["'][^>]*>/gi,
      /<video\b[^>]*src\s*=\s*["']([^"']+)["'][^>]*>/gi
    ];
    for (const pattern of patterns) {
      let match;
      while ((match = pattern.exec(html)) !== null && links.length < maximumLinksPerPage * 2) {
        const resolvedLink = resolveLink(match[1], baseUrl);
        if (resolvedLink) links.push(resolvedLink.href);
      }
    }
    return [...new Set(links)];
  }
  function getResults() { return results.slice().sort((a, b) => b.score - a.score).slice(0, maximumResults); }
  function getCandidates() { return candidates.slice().sort((a, b) => b.score - a.score).slice(0, maximumCandidates); }
  function getStatistics() {
    return {
      frontierSize: frontier.length, completedUrls: completedUrls.size, failedUrls: failedUrls.size,
      results: results.length, candidates: candidates.length, activeWorkers, maximumDepth,
      maximumFrontierItems, maximumResults, maximumCandidates, policy: policy.getPolicyStatistics()
    };
  }
  function clear() {
    frontier.length = 0; frontierKeys.clear(); completedUrls.clear(); failedUrls.clear();
    results.length = 0; candidates.length = 0; activeWorkers = 0; frontierClosed = false;
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
  createGlobalFrontier, PRIORITY_WEIGHTS, DEFAULT_MAXIMUM_FRONTIER_ITEMS, DEFAULT_MAXIMUM_RESULTS,
  DEFAULT_MAXIMUM_DEPTH, DEFAULT_MAXIMUM_LINKS_PER_PAGE, DEFAULT_MAXIMUM_CANDIDATES
};
