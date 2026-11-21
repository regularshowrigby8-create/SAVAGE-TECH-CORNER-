"use strict";
const { guardUrl, getHostname } = require("./global-resolver/policy");
const { detectSourceKind, parseAddonManifest } = require("./global-resolver/adapters");
const STORAGE_KEY = "zeroplay.global.sources.v1";
const HEALTH_STORAGE_KEY = "zeroplay.global.source-health.v1";
const DEFAULT_SOURCE_PRIORITY = 60;
const DEFAULT_REQUEST_TIMEOUT_MS = 10000;
const MAXIMUM_SOURCES = 200;
function createGlobalSourceOnboarding(options) {
  const config = options || {};
  const requestTimeoutMs = clampInteger(config.requestTimeoutMs, 2000, 30000, DEFAULT_REQUEST_TIMEOUT_MS);
  const maximumSources = clampInteger(config.maximumSources, 1, 1000, MAXIMUM_SOURCES);
  return { addSource, validateSource, getSources, getSource, enableSource, disableSource, removeSource, updateSourceHealth, getStatistics, clearAllSources };
  function getStoredSources() {
    try {
      const rawSources = localStorage.getItem(STORAGE_KEY);
      if (!rawSources) return [];
      const parsedSources = JSON.parse(rawSources);
      return Array.isArray(parsedSources) ? parsedSources : [];
    } catch (error) { return []; }
  }
  function saveStoredSources(sources) {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(sources)); return true; } catch (error) { return false; }
  }
  function getStoredHealth() {
    try {
      const rawHealth = localStorage.getItem(HEALTH_STORAGE_KEY);
      if (!rawHealth) return {};
      const parsedHealth = JSON.parse(rawHealth);
      return parsedHealth || {};
    } catch (error) { return {}; }
  }
  function saveStoredHealth(health) {
    try { localStorage.setItem(HEALTH_STORAGE_KEY, JSON.stringify(health)); return true; } catch (error) { return false; }
  }
  async function validateSource(sourceUrl) {
    const parsedUrl = guardUrl(sourceUrl, { allowTorrents: false });
    if (!parsedUrl) return { valid: false, reason: "invalid-or-unsupported-url", sourceKind: "unknown" };
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), requestTimeoutMs);
    try {
      const response = await fetch(parsedUrl.href, {
        method: "GET", signal: controller.signal, redirect: "follow",
        headers: { Accept: "text/html,application/json,application/vnd.stremio+json,*/*", "User-Agent": "ZeroPlayTV/1.0" }
      });
      if (!response.ok) return { valid: false, reason: `http-${response.status}`, sourceKind: "unknown" };
      const contentType = String(response.headers.get("content-type") || "").toLowerCase();
      let body = null;
      if (contentType.includes("application/json") || parsedUrl.pathname.endsWith(".json")) body = await response.json();
      else body = await response.text();
      const detectedKind = detectSourceKind(parsedUrl.href, { contentType });
      let manifestValidation = null;
      if (detectedKind === "addon-manifest") {
        manifestValidation = parseAddonManifest(body, parsedUrl.href, null);
        if (!manifestValidation.valid) return { valid: false, reason: "invalid-addon-manifest", sourceKind: detectedKind };
      }
      return { valid: true, url: parsedUrl.href, sourceKind: detectedKind, contentType, manifest: manifestValidation, title: extractTitleFromResponse(body, detectedKind) };
    } catch (error) {
      return { valid: false, reason: error.name === "AbortError" ? "validation-timeout" : "validation-failed", sourceKind: "unknown" };
    } finally { clearTimeout(timeout); }
  }
  function extractTitleFromResponse(body, sourceKind) {
    if (sourceKind === "addon-manifest" && body && typeof body === "object") return body.name || body.id || "";
    if (typeof body === "string") {
      const titleMatch = body.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
      if (titleMatch) return titleMatch[1].trim();
    }
    return "";
  }
  async function addSource(sourceInput) {
    const input = sourceInput || {};
    const parsedUrl = guardUrl(input.url, { allowTorrents: false });
    if (!parsedUrl) return { success: false, reason: "invalid-or-unsupported-url" };
    const existingSources = getStoredSources();
    if (existingSources.length >= maximumSources) return { success: false, reason: "source-limit-reached" };
    const duplicateSource = existingSources.find((source) => source.url === parsedUrl.href);
    if (duplicateSource) return { success: false, reason: "source-already-added", source: duplicateSource };
    const validation = await validateSource(parsedUrl.href);
    if (!validation.valid) return { success: false, reason: validation.reason || "validation-failed", validation };
    const source = {
      id: createSourceId(parsedUrl.href), url: parsedUrl.href, name: input.name || validation.title || getHostname(parsedUrl),
      kind: validation.sourceKind, enabled: true, priority: clampInteger(input.priority || DEFAULT_SOURCE_PRIORITY, 0, 100, DEFAULT_SOURCE_PRIORITY),
      addedAt: Date.now(), updatedAt: Date.now(),
      capabilities: { movies: true, series: true, search: true, directFiles: true, playlists: true, metadata: true },
      metadata: { contentType: validation.contentType, manifest: validation.manifest?.metadata || {} }
    };
    const saved = saveStoredSources([...existingSources, source]);
    if (!saved) return { success: false, reason: "source-storage-failed" };
    return { success: true, source, validation };
  }
  function getSources(options) {
    const config = options || {};
    return getStoredSources().filter((source) => {
      if (config.enabledOnly && !source.enabled) return false;
      if (config.kind && source.kind !== config.kind) return false;
      return true;
    }).sort((a, b) => b.priority - a.priority);
  }
  function getSource(sourceId) { return getStoredSources().find((source) => source.id === sourceId) || null; }
  function enableSource(sourceId) { return updateSourceState(sourceId, true); }
  function disableSource(sourceId) { return updateSourceState(sourceId, false); }
  function updateSourceState(sourceId, enabled) {
    const sources = getStoredSources();
    const source = sources.find((item) => item.id === sourceId);
    if (!source) return { success: false, reason: "source-not-found" };
    source.enabled = enabled;
    source.updatedAt = Date.now();
    const saved = saveStoredSources(sources);
    return { success: saved, reason: saved ? "source-updated" : "source-storage-failed", source };
  }
  function removeSource(sourceId) {
    const sources = getStoredSources();
    const filteredSources = sources.filter((source) => source.id !== sourceId);
    if (filteredSources.length === sources.length) return { success: false, reason: "source-not-found" };
    const saved = saveStoredSources(filteredSources);
    if (!saved) return { success: false, reason: "source-storage-failed" };
    const health = getStoredHealth();
    delete health[sourceId];
    saveStoredHealth(health);
    return { success: true, reason: "source-removed" };
  }
  function updateSourceHealth(sourceId, outcome) {
    const health = getStoredHealth();
    const currentHealth = health[sourceId] || { successes: 0, failures: 0, consecutiveFailures: 0, lastSuccessAt: 0, lastFailureAt: 0, averageLatencyMs: 0, cooldownUntil: 0, lastError: null };
    const latency = Number(outcome?.latencyMs || 0);
    if (outcome?.success) {
      currentHealth.successes += 1; currentHealth.consecutiveFailures = 0; currentHealth.lastSuccessAt = Date.now(); currentHealth.lastError = null;
      if (latency > 0) currentHealth.averageLatencyMs = currentHealth.averageLatencyMs === 0 ? latency : Math.round((currentHealth.averageLatencyMs + latency) / 2);
    } else {
      currentHealth.failures += 1; currentHealth.consecutiveFailures += 1; currentHealth.lastFailureAt = Date.now(); currentHealth.lastError = outcome?.error || "unknown-error";
      if (currentHealth.consecutiveFailures >= 4) currentHealth.cooldownUntil = Date.now() + 300000;
    }
    health[sourceId] = currentHealth;
    saveStoredHealth(health);
    return { success: true, health: currentHealth };
  }
  function getStatistics() {
    const sources = getStoredSources();
    const health = getStoredHealth();
    const enabledSources = sources.filter((source) => source.enabled).length;
    const healthySources = sources.filter((source) => {
      const sourceHealth = health[source.id];
      if (!sourceHealth) return true;
      return sourceHealth.consecutiveFailures === 0;
    }).length;
    const coolingDownSources = sources.filter((source) => {
      const sourceHealth = health[source.id];
      if (!sourceHealth) return false;
      return sourceHealth.cooldownUntil > Date.now();
    }).length;
    return { totalSources: sources.length, enabledSources, healthySources, coolingDownSources, maximumSources };
  }
  function clearAllSources() {
    const sourcesCleared = saveStoredSources([]);
    const healthCleared = saveStoredHealth({});
    return { success: sourcesCleared && healthCleared };
  }
}
function createSourceId(sourceUrl) {
  const crypto = require("node:crypto");
  return crypto.createHash("sha256").update(String(sourceUrl)).digest("hex").slice(0, 24);
}
function clampInteger(value, minimum, maximum) {
  const parsedValue = Number(value);
  if (!Number.isFinite(parsedValue)) return minimum;
  return Math.max(minimum, Math.min(maximum, Math.floor(parsedValue)));
}
module.exports = {
  createGlobalSourceOnboarding, STORAGE_KEY, HEALTH_STORAGE_KEY, DEFAULT_SOURCE_PRIORITY, DEFAULT_REQUEST_TIMEOUT_MS, MAXIMUM_SOURCES
};
