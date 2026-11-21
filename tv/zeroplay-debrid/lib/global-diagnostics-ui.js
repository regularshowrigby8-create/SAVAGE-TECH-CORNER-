"use strict";
const DIAGNOSTICS_CONTAINER_SELECTOR = "[data-global-diagnostics]";
const STATUS_SELECTOR = "[data-global-status]";
const ENDPOINT_SELECTOR = "[data-global-endpoint]";
const CACHE_SELECTOR = "[data-global-cache]";
const RATE_LIMIT_SELECTOR = "[data-global-rate-limit]";
const SOURCES_SELECTOR = "[data-global-sources]";
const RESULTS_SELECTOR = "[data-global-results]";
const WORKERS_SELECTOR = "[data-global-workers]";
function createGlobalDiagnosticsUi(options) {
  const config = options || {};
  const containerSelector = config.containerSelector || DIAGNOSTICS_CONTAINER_SELECTOR;
  const statusSelector = config.statusSelector || STATUS_SELECTOR;
  const endpointSelector = config.endpointSelector || ENDPOINT_SELECTOR;
  const cacheSelector = config.cacheSelector || CACHE_SELECTOR;
  const rateLimitSelector = config.rateLimitSelector || RATE_LIMIT_SELECTOR;
  const sourcesSelector = config.sourcesSelector || SOURCES_SELECTOR;
  const resultsSelector = config.resultsSelector || RESULTS_SELECTOR;
  const workersSelector = config.workersSelector || WORKERS_SELECTOR;
  return {
    render, updateStatus, updateEndpoint, updateCache, updateRateLimit, updateSources, updateResults, updateWorkers,
    showLoading, showFallback, showError, clear
  };
  function getContainer() { return document.querySelector(containerSelector); }
  function getElement(selector) {
    const container = getContainer();
    if (!container) return null;
    return container.querySelector(selector);
  }
  function render(state) {
    const normalizedState = normalizeState(state);
    updateStatus(normalizedState.status);
    updateEndpoint(normalizedState.endpoint);
    updateCache(normalizedState.cache);
    updateRateLimit(normalizedState.rateLimit);
    updateSources(normalizedState.sources);
    updateResults(normalizedState.results);
    updateWorkers(normalizedState.workers);
  }
  function updateStatus(status) {
    const statusElement = getElement(statusSelector);
    if (!statusElement) return;
    statusElement.textContent = status || "Idle";
  }
  function updateEndpoint(endpoint) {
    const endpointElement = getElement(endpointSelector);
    if (!endpointElement) return;
    const normalizedEndpoint = endpoint || {};
    if (normalizedEndpoint.available === true) endpointElement.textContent = "Endpoint ready";
    else if (normalizedEndpoint.available === false) endpointElement.textContent = "Endpoint unavailable";
    else endpointElement.textContent = "Endpoint unknown";
  }
  function updateCache(cache) {
    const cacheElement = getElement(cacheSelector);
    if (!cacheElement) return;
    const normalizedCache = cache || {};
    if (normalizedCache.cached === true) cacheElement.textContent = "Cached";
    else if (normalizedCache.cached === false) cacheElement.textContent = "Fresh";
    else cacheElement.textContent = "Cache unknown";
  }
  function updateRateLimit(rateLimit) {
    const rateLimitElement = getElement(rateLimitSelector);
    if (!rateLimitElement) return;
    const normalizedRateLimit = rateLimit || {};
    if (normalizedRateLimit.remaining === undefined) rateLimitElement.textContent = "Rate limit unknown";
    else if (normalizedRateLimit.remaining <= 0) rateLimitElement.textContent = "Rate limited";
    else rateLimitElement.textContent = `${normalizedRateLimit.remaining} requests left`;
  }
  function updateSources(sources) {
    const sourcesElement = getElement(sourcesSelector);
    if (!sourcesElement) return;
    const normalizedSources = sources || {};
    sourcesElement.textContent = `${normalizedSources.enabledSources || 0} sources · ${normalizedSources.healthySources || 0} healthy`;
  }
  function updateResults(results) {
    const resultsElement = getElement(resultsSelector);
    if (!resultsElement) return;
    const normalizedResults = results || {};
    resultsElement.textContent = `${normalizedResults.count || 0} global results`;
  }
  function updateWorkers(workers) {
    const workersElement = getElement(workersSelector);
    if (!workersElement) return;
    const normalizedWorkers = workers || {};
    workersElement.textContent = `${normalizedWorkers.active || 0} active`;
  }
  function showLoading(message) { updateStatus(message || "Searching globally"); }
  function showFallback(message) { updateStatus(message || "Using local search"); }
  function showError(message) { updateStatus(message || "Global search unavailable"); }
  function clear() {
    updateStatus("Idle");
    updateEndpoint({ available: null });
    updateCache({ cached: null });
    updateRateLimit({ remaining: undefined });
    updateSources({ enabledSources: 0, healthySources: 0 });
    updateResults({ count: 0 });
    updateWorkers({ active: 0 });
  }
}
function normalizeState(state) {
  const input = state || {};
  return {
    status: input.status || "Idle",
    endpoint: input.endpoint || { available: null },
    cache: input.cache || { cached: null },
    rateLimit: input.rateLimit || { remaining: undefined },
    sources: input.sources || { enabledSources: 0, healthySources: 0 },
    results: input.results || { count: 0 },
    workers: input.workers || { active: 0 }
  };
}
module.exports = {
  createGlobalDiagnosticsUi, DIAGNOSTICS_CONTAINER_SELECTOR, STATUS_SELECTOR, ENDPOINT_SELECTOR,
  CACHE_SELECTOR, RATE_LIMIT_SELECTOR, SOURCES_SELECTOR, RESULTS_SELECTOR, WORKERS_SELECTOR
};
