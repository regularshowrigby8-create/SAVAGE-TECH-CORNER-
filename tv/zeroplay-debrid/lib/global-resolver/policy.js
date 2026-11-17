"use strict";
const { URL } = require("node:url");
const MAX_FILE_SIZE_BYTES = 5 * 1000 * 1000 * 1000;
const MAX_URL_LENGTH = 2048;
const MAX_REDIRECTS = 4;
const DEFAULT_REQUEST_TIMEOUT_MS = 8000;
const DEFAULT_GLOBAL_CONCURRENCY = 12;
const DEFAULT_HOST_CONCURRENCY = 2;
const DEFAULT_HOST_REQUESTS_PER_MINUTE = 20;
const DEFAULT_RETRY_ATTEMPTS = 3;
const DEFAULT_BACKOFF_BASE_MS = 750;
const DEFAULT_BACKOFF_MAXIMUM_MS = 30000;
const ALLOWED_PROTOCOLS = new Set(["http:", "https:"]);
const BLOCKED_HOSTNAMES = new Set(["localhost","127.0.0.1","0.0.0.0","::1","[::1]"]);
/* 100.64.0.0/10 is CGNAT, which is also what Tailscale and many container
   networks hand out: a resolvable, routable-looking private address. The batch
   omitted it (and the other special-purpose blocks), so a feed could point the
   resolver at a private host and the guard would allow it. Kept as a list of
   prefixes so the existing startswith check still applies. */
const PRIVATE_IPV4_PREFIXES = [
  "10.","172.16.","172.17.","172.18.","172.19.","172.20.","172.21.","172.22.","172.23.","172.24.",
  "172.25.","172.26.","172.27.","172.28.","172.29.","172.30.","172.31.","192.168.",
  "100.64.","100.65.","100.66.","100.67.","100.68.","100.69.","100.70.","100.71.","100.72.","100.73.",
  "100.74.","100.75.","100.76.","100.77.","100.78.","100.79.","100.80.","100.81.","100.82.","100.83.",
  "100.84.","100.85.","100.86.","100.87.","100.88.","100.89.","100.90.","100.91.","100.92.","100.93.",
  "100.94.","100.95.","100.96.","100.97.","100.98.","100.99.","100.100.","100.101.","100.102.","100.103.",
  "100.104.","100.105.","100.106.","100.107.","100.108.","100.109.","100.110.","100.111.","100.112.",
  "100.113.","100.114.","100.115.","100.116.","100.117.","100.118.","100.119.","100.120.","100.121.",
  "100.122.","100.123.","100.124.","100.125.","100.126.","100.127.",
  "198.18.","198.19.","198.51.100.","203.0.113."
];
/* IPv6 ranges the string prefix list cannot express. */
const PRIVATE_IPV6_PATTERNS = [/^fc[0-9a-f]{2}:/, /^fd[0-9a-f]{2}:/, /^fe[89ab][0-9a-f]:/, /^ff[0-9a-f]{2}:/, /^::ffff:/];
const DIRECT_MEDIA_EXTENSIONS = [".mp4",".m4v",".mov",".webm",".mkv",".avi",".m3u8",".mpd"];
const PLAYLIST_EXTENSIONS = [".m3u",".m3u8"];
const MANIFEST_EXTENSIONS = [".mpd"];
const TORRENT_INDICATORS = ["magnet:",".torrent","torrent","magnet","peer","tracker"];
const CREDENTIAL_INDICATORS = ["username=","user=","password=","pass=","token=","api_key=","apikey=","auth=","session="];
const SOURCE_KINDS = {
  DIRECT_FILE: "direct-file",
  HLS_PLAYLIST: "hls-playlist",
  DASH_MANIFEST: "dash-manifest",
  WEB_PAGE: "web-page",
  SEARCH_PAGE: "search-page",
  DETAIL_PAGE: "detail-page",
  WATCH_PAGE: "watch-page",
  API_ENDPOINT: "api-endpoint",
  ADDON_MANIFEST: "addon-manifest",
  UNKNOWN: "unknown"
};
function normalizeUrl(input) {
  if (!input) return null;
  let rawUrl = String(input).trim();
  if (!rawUrl) return null;
  if (!/^https?:\/\//i.test(rawUrl)) rawUrl = `https://${rawUrl}`;
  let parsedUrl;
  try { parsedUrl = new URL(rawUrl); } catch (error) { return null; }
  if (!ALLOWED_PROTOCOLS.has(parsedUrl.protocol)) return null;
  if (parsedUrl.href.length > MAX_URL_LENGTH) return null;
  return parsedUrl;
}
function getHostname(url) {
  if (!url) return null;
  try {
    const parsedUrl = url instanceof URL ? url : new URL(String(url));
    return parsedUrl.hostname.toLowerCase();
  } catch (error) { return null; }
}
function isPrivateHost(hostname) {
  if (!hostname) return true;
  const normalizedHost = String(hostname).toLowerCase().trim();
  if (!normalizedHost) return true;
  if (BLOCKED_HOSTNAMES.has(normalizedHost)) return true;
  if (normalizedHost.endsWith(".local") || normalizedHost.endsWith(".internal") || normalizedHost.endsWith(".localhost")) return true;
  if (PRIVATE_IPV4_PREFIXES.some((prefix) => normalizedHost.startsWith(prefix))) return true;
  const bareHost = normalizedHost.replace(/^\[|\]$/g, "");
  if (PRIVATE_IPV6_PATTERNS.some((pattern) => pattern.test(bareHost))) return true;
  if (normalizedHost === "169.254.169.254" || normalizedHost === "metadata.google.internal") return true;
  /* Unqualified single-label names resolve through local search domains on
     most networks, so "addon" can mean a machine on the LAN. */
  if (!normalizedHost.includes(".") && !normalizedHost.includes(":")) return true;
  return false;
}
function containsTorrentIndicator(input) {
  const value = String(input || "").toLowerCase();
  if (!value) return false;
  return TORRENT_INDICATORS.some((indicator) => value.includes(indicator));
}
function containsCredentialIndicator(url) {
  const parsedUrl = url instanceof URL ? url : normalizeUrl(url);
  if (!parsedUrl) return false;
  const search = parsedUrl.search.toLowerCase();
  return CREDENTIAL_INDICATORS.some((indicator) => search.includes(indicator));
}
function guardUrl(input, options) {
  const config = options || {};
  const parsedUrl = normalizeUrl(input);
  if (!parsedUrl) return null;
  const hostname = getHostname(parsedUrl);
  if (!hostname || isPrivateHost(hostname)) return null;
  if (containsCredentialIndicator(parsedUrl)) return null;
  if (config.allowTorrents !== true && containsTorrentIndicator(parsedUrl.href)) return null;
  return parsedUrl;
}
function classifyUrl(input) {
  const parsedUrl = normalizeUrl(input);
  if (!parsedUrl) return SOURCE_KINDS.UNKNOWN;
  const pathname = parsedUrl.pathname.toLowerCase();
  const search = parsedUrl.search.toLowerCase();
  const href = parsedUrl.href.toLowerCase();
  if (pathname.endsWith(".torrent") || href.startsWith("magnet:")) return SOURCE_KINDS.UNKNOWN;
  if (PLAYLIST_EXTENSIONS.some((e) => pathname.endsWith(e))) return SOURCE_KINDS.HLS_PLAYLIST;
  if (MANIFEST_EXTENSIONS.some((e) => pathname.endsWith(e))) return SOURCE_KINDS.DASH_MANIFEST;
  if (DIRECT_MEDIA_EXTENSIONS.some((e) => pathname.endsWith(e))) return SOURCE_KINDS.DIRECT_FILE;
  if (search.includes("q=") || search.includes("query=") || search.includes("search=") || pathname.includes("/search")) return SOURCE_KINDS.SEARCH_PAGE;
  if (pathname.includes("/watch") || pathname.includes("/play") || pathname.includes("/stream") || pathname.includes("/embed") || pathname.includes("/player")) return SOURCE_KINDS.WATCH_PAGE;
  if (pathname.includes("/title/") || pathname.includes("/movie/") || pathname.includes("/series/") || pathname.includes("/tv/") || pathname.includes("/anime/")) return SOURCE_KINDS.DETAIL_PAGE;
  if (pathname.endsWith(".json") || pathname.includes("/api/") || search.includes("format=json")) return SOURCE_KINDS.API_ENDPOINT;
  return SOURCE_KINDS.WEB_PAGE;
}
function isDirectMediaUrl(input) {
  const parsedUrl = normalizeUrl(input);
  if (!parsedUrl) return false;
  const pathname = parsedUrl.pathname.toLowerCase();
  return DIRECT_MEDIA_EXTENSIONS.some((e) => pathname.endsWith(e));
}
function isPlaylistUrl(input) {
  const parsedUrl = normalizeUrl(input);
  if (!parsedUrl) return false;
  const pathname = parsedUrl.pathname.toLowerCase();
  return PLAYLIST_EXTENSIONS.some((e) => pathname.endsWith(e));
}
function isManifestUrl(input) {
  const parsedUrl = normalizeUrl(input);
  if (!parsedUrl) return false;
  const pathname = parsedUrl.pathname.toLowerCase();
  return MANIFEST_EXTENSIONS.some((e) => pathname.endsWith(e));
}
function isSameOrigin(firstUrl, secondUrl) {
  const first = normalizeUrl(firstUrl);
  const second = normalizeUrl(secondUrl);
  if (!first || !second) return false;
  return first.origin === second.origin;
}
function createCrawlPolicy(options) {
  const config = options || {};
  const globalConcurrency = clampPositiveInteger(config.globalConcurrency, 1, 64, DEFAULT_GLOBAL_CONCURRENCY);
  const hostConcurrency = clampPositiveInteger(config.hostConcurrency, 1, 8, DEFAULT_HOST_CONCURRENCY);
  const hostRequestsPerMinute = clampPositiveInteger(config.hostRequestsPerMinute, 1, 240, DEFAULT_HOST_REQUESTS_PER_MINUTE);
  const requestTimeoutMs = clampPositiveInteger(config.requestTimeoutMs, 1000, 30000, DEFAULT_REQUEST_TIMEOUT_MS);
  const retryAttempts = clampPositiveInteger(config.retryAttempts, 0, 6, DEFAULT_RETRY_ATTEMPTS);
  const activeRequests = new Set();
  const hostActiveRequests = new Map();
  const hostRequestTimes = new Map();
  const hostFailures = new Map();
  const hostBackoffUntil = new Map();
  return {
    globalConcurrency, hostConcurrency, hostRequestsPerMinute, requestTimeoutMs, retryAttempts,
    maximumFileSizeBytes: MAX_FILE_SIZE_BYTES,
    canRequest, reserveRequest, releaseRequest, getBackoffDelay, recordFailure, recordSuccess, getHostState, getPolicyStatistics
  };
  function canRequest(url) {
    const parsedUrl = guardUrl(url, { allowTorrents: false });
    if (!parsedUrl) return false;
    const hostname = getHostname(parsedUrl);
    if (!hostname) return false;
    if (activeRequests.size >= globalConcurrency) return false;
    const activeForHost = hostActiveRequests.get(hostname) || 0;
    if (activeForHost >= hostConcurrency) return false;
    const backoffUntil = hostBackoffUntil.get(hostname) || 0;
    if (Date.now() < backoffUntil) return false;
    const requestTimes = hostRequestTimes.get(hostname) || [];
    const oneMinuteAgo = Date.now() - 60000;
    const recentRequests = requestTimes.filter((t) => t > oneMinuteAgo);
    if (recentRequests.length >= hostRequestsPerMinute) return false;
    return true;
  }
  function reserveRequest(url) {
    const parsedUrl = guardUrl(url, { allowTorrents: false });
    if (!parsedUrl) return null;
    const hostname = getHostname(parsedUrl);
    if (!hostname || !canRequest(parsedUrl)) return null;
    const requestToken = `${Date.now()}-${Math.random()}`;
    activeRequests.add(requestToken);
    const activeForHost = hostActiveRequests.get(hostname) || 0;
    hostActiveRequests.set(hostname, activeForHost + 1);
    const requestTimes = hostRequestTimes.get(hostname) || [];
    requestTimes.push(Date.now());
    hostRequestTimes.set(hostname, requestTimes);
    return { token: requestToken, url: parsedUrl, hostname };
  }
  function releaseRequest(reservation) {
    if (!reservation || !reservation.token) return;
    activeRequests.delete(reservation.token);
    const hostname = reservation.hostname;
    const activeForHost = hostActiveRequests.get(hostname) || 0;
    hostActiveRequests.set(hostname, Math.max(0, activeForHost - 1));
  }
  function getBackoffDelay(consecutiveFailures) {
    const attempt = Math.max(1, Number(consecutiveFailures || 1));
    const exponentialDelay = DEFAULT_BACKOFF_BASE_MS * Math.pow(2, attempt - 1);
    const boundedDelay = Math.min(DEFAULT_BACKOFF_MAXIMUM_MS, exponentialDelay);
    const jitter = Math.random() * 250;
    return Math.round(boundedDelay + jitter);
  }
  function recordFailure(url, options) {
    const config = options || {};
    const hostname = getHostname(url);
    if (!hostname) return null;
    const currentFailures = (hostFailures.get(hostname) || 0) + 1;
    hostFailures.set(hostname, currentFailures);
    const backoffDelay = getBackoffDelay(currentFailures);
    hostBackoffUntil.set(hostname, Date.now() + backoffDelay);
    return { hostname, consecutiveFailures: currentFailures, backoffDelay, statusCode: config.statusCode || null, errorMessage: config.errorMessage || null };
  }
  function recordSuccess(url) {
    const hostname = getHostname(url);
    if (!hostname) return;
    hostFailures.set(hostname, 0);
    hostBackoffUntil.set(hostname, 0);
  }
  function getHostState(url) {
    const hostname = getHostname(url);
    if (!hostname) return null;
    return {
      hostname,
      activeRequests: hostActiveRequests.get(hostname) || 0,
      recentFailures: hostFailures.get(hostname) || 0,
      backoffUntil: hostBackoffUntil.get(hostname) || 0,
      recentRequestCount: (hostRequestTimes.get(hostname) || []).length
    };
  }
  function getPolicyStatistics() {
    return {
      activeRequests: activeRequests.size,
      globalConcurrency, hostConcurrency, hostRequestsPerMinute, requestTimeoutMs, retryAttempts,
      maximumFileSizeBytes: MAX_FILE_SIZE_BYTES,
      trackedHosts: hostActiveRequests.size,
      backedOffHosts: [...hostBackoffUntil.entries()].filter((e) => e[1] > Date.now()).length
    };
  }
}
function clampPositiveInteger(value, minimum, maximum, fallback) {
  const parsedValue = Number(value);
  if (!Number.isFinite(parsedValue)) return fallback;
  const integer = Math.floor(parsedValue);
  return Math.max(minimum, Math.min(maximum, integer));
}
module.exports = {
  MAX_FILE_SIZE_BYTES, MAX_URL_LENGTH, MAX_REDIRECTS, DEFAULT_REQUEST_TIMEOUT_MS,
  DEFAULT_GLOBAL_CONCURRENCY, DEFAULT_HOST_CONCURRENCY, DEFAULT_HOST_REQUESTS_PER_MINUTE,
  DEFAULT_RETRY_ATTEMPTS, DEFAULT_BACKOFF_BASE_MS, DEFAULT_BACKOFF_MAXIMUM_MS, SOURCE_KINDS,
  normalizeUrl, getHostname, isPrivateHost, containsTorrentIndicator, containsCredentialIndicator,
  guardUrl, classifyUrl, isDirectMediaUrl, isPlaylistUrl, isManifestUrl, isSameOrigin, createCrawlPolicy
};
