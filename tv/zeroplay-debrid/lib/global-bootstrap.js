"use strict";
const { createGlobalClient } = require("./global-client");
const { createGlobalResultsUi } = require("./global-results-ui");
const { createGlobalSourceOnboarding } = require("./global-source-onboarding");
const { createGlobalSourceManagerUi } = require("./global-source-manager-ui");
const { createGlobalDiagnosticsUi } = require("./global-diagnostics-ui");
function createGlobalBootstrap(options) {
  const config = options || {};
  const globalClient = createGlobalClient({
    globalEndpoint: config.globalEndpoint, requestTimeoutMs: config.requestTimeoutMs,
    globalDelayMs: config.globalDelayMs, maximumResults: config.maximumResults
  });
  const resultsUi = createGlobalResultsUi({
    playerSelector: config.playerSelector, resultsContainerSelector: config.resultsContainerSelector, statusSelector: config.statusSelector
  });
  const onboarding = createGlobalSourceOnboarding({
    requestTimeoutMs: config.sourceValidationTimeoutMs, maximumSources: config.maximumSources
  });
  const sourceManagerUi = createGlobalSourceManagerUi({
    onboarding,
    containerSelector: config.sourceManagerSelector, listSelector: config.sourceListSelector,
    summarySelector: config.sourceSummarySelector, statusSelector: config.sourceStatusSelector
  });
  const diagnosticsUi = createGlobalDiagnosticsUi({
    containerSelector: config.diagnosticsSelector, statusSelector: config.diagnosticsStatusSelector,
    endpointSelector: config.diagnosticsEndpointSelector, cacheSelector: config.diagnosticsCacheSelector,
    rateLimitSelector: config.diagnosticsRateLimitSelector, sourcesSelector: config.diagnosticsSourcesSelector,
    resultsSelector: config.diagnosticsResultsSelector, workersSelector: config.diagnosticsWorkersSelector
  });
  return {
    globalClient, resultsUi, onboarding, sourceManagerUi, diagnosticsUi,
    initialize, search, addSource, renderSources, clearGlobalResults, cancelActiveSearch
  };
  function initialize() {
    sourceManagerUi.renderSources();
    diagnosticsUi.clear();
    resultsUi.clearGlobalResults();
  }
  async function search(searchRequest, callbacks) {
    const callbackConfig = callbacks || {};
    diagnosticsUi.showLoading();
    return await globalClient.search(searchRequest, {
      onLocalSearch: callbackConfig.onLocalSearch,
      onGlobalSearch: async (request, context) => {
        const endpointResult = await globalClient.resolveTitle(request);
        if (endpointResult.success) {
          diagnosticsUi.updateEndpoint({ available: true });
          diagnosticsUi.updateCache({ cached: endpointResult.cached });
          diagnosticsUi.updateRateLimit(endpointResult.rateLimit);
          return endpointResult.results;
        }
        diagnosticsUi.updateEndpoint({ available: false });
        diagnosticsUi.showFallback();
        return [];
      },
      onResults: (payload) => {
        if (typeof callbackConfig.onResults === "function") callbackConfig.onResults(payload);
        resultsUi.renderGlobalResults(payload.results.filter((result) => result.origin === "global"), {
          request: payload.request, searchToken: payload.searchToken
        });
        diagnosticsUi.updateResults({ count: payload.results.filter((result) => result.origin === "global").length });
      },
      onGlobalProgress: (payload) => {
        diagnosticsUi.updateWorkers({ active: payload.progress?.activeWorkers || 0 });
      },
      onSearchError: (payload) => {
        if (payload.source === "global") diagnosticsUi.showFallback();
        if (typeof callbackConfig.onSearchError === "function") callbackConfig.onSearchError(payload);
      },
      onSearchComplete: (payload) => {
        diagnosticsUi.render({
          status: payload.globalResultCount > 0 ? "Global search complete" : "No global results",
          endpoint: { available: payload.globalSucceeded },
          cache: { cached: false },
          rateLimit: { remaining: 0 },
          sources: {
            enabledSources: payload.statistics?.sources?.enabledSources || 0,
            healthySources: payload.statistics?.sources?.healthySources || 0
          },
          results: { count: payload.globalResultCount },
          workers: { active: 0 }
        });
        if (typeof callbackConfig.onSearchComplete === "function") callbackConfig.onSearchComplete(payload);
      }
    });
  }
  async function addSource(sourceInput) {
    const result = await onboarding.addSource(sourceInput);
    renderSources();
    return result;
  }
  function renderSources() { sourceManagerUi.renderSources(); }
  function clearGlobalResults() {
    resultsUi.clearGlobalResults();
    diagnosticsUi.clear();
  }
  function cancelActiveSearch() {
    globalClient.cancelActiveSearch();
    diagnosticsUi.showFallback("Global search cancelled");
  }
}
module.exports = { createGlobalBootstrap };
