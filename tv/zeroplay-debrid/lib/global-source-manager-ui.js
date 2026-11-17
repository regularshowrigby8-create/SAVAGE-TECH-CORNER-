"use strict";
const SOURCE_MANAGER_CONTAINER_SELECTOR = "[data-global-source-manager]";
const SOURCE_LIST_SELECTOR = "[data-global-source-list]";
const SOURCE_SUMMARY_SELECTOR = "[data-global-source-summary]";
const SOURCE_STATUS_SELECTOR = "[data-global-source-status]";
function createGlobalSourceManagerUi(options) {
  const config = options || {};
  const onboarding = config.onboarding;
  const containerSelector = config.containerSelector || SOURCE_MANAGER_CONTAINER_SELECTOR;
  const listSelector = config.listSelector || SOURCE_LIST_SELECTOR;
  const summarySelector = config.summarySelector || SOURCE_SUMMARY_SELECTOR;
  const statusSelector = config.statusSelector || SOURCE_STATUS_SELECTOR;
  return { renderSources, renderSummary, setStatus, showLoading, showError, clear };
  function getContainer() { return document.querySelector(containerSelector); }
  function getListElement() {
    const container = getContainer();
    if (!container) return null;
    return container.querySelector(listSelector) || container;
  }
  function getSummaryElement() {
    const container = getContainer();
    if (!container) return null;
    return container.querySelector(summarySelector);
  }
  function getStatusElement() {
    const container = getContainer();
    if (!container) return null;
    return container.querySelector(statusSelector);
  }
  function renderSources() {
    const listElement = getListElement();
    if (!listElement) return { rendered: 0, reason: "source-list-not-found" };
    const sources = onboarding.getSources();
    listElement.innerHTML = "";
    let renderedCount = 0;
    for (const source of sources) {
      const sourceCard = createSourceCard(source);
      if (sourceCard) { listElement.appendChild(sourceCard); renderedCount += 1; }
    }
    renderSummary();
    return { rendered: renderedCount, total: sources.length };
  }
  function createSourceCard(source) {
    if (!source || !source.id) return null;
    const card = document.createElement("div");
    card.className = "zp-global-source-card";
    card.setAttribute("data-global-source-id", source.id);
    card.innerHTML = `
      <div class="zp-global-source-header">
        <span class="zp-global-source-name"></span>
        <span class="zp-global-source-kind"></span>
      </div>
      <div class="zp-global-source-url"></div>
      <div class="zp-global-source-health"></div>
      <div class="zp-global-source-actions">
        <button type="button" class="zp-global-source-toggle">Enable</button>
        <button type="button" class="zp-global-source-remove">Remove</button>
      </div>
    `;
    const nameElement = card.querySelector(".zp-global-source-name");
    const kindElement = card.querySelector(".zp-global-source-kind");
    const urlElement = card.querySelector(".zp-global-source-url");
    const healthElement = card.querySelector(".zp-global-source-health");
    const toggleButton = card.querySelector(".zp-global-source-toggle");
    const removeButton = card.querySelector(".zp-global-source-remove");
    if (nameElement) nameElement.textContent = source.name || "Unnamed source";
    if (kindElement) kindElement.textContent = formatSourceKind(source.kind);
    if (urlElement) urlElement.textContent = source.url || "";
    if (healthElement) healthElement.textContent = buildHealthText(source.id);
    if (toggleButton) {
      updateToggleButton(toggleButton, source.enabled);
      toggleButton.addEventListener("click", () => {
        const updatedSource = source.enabled ? onboarding.disableSource(source.id) : onboarding.enableSource(source.id);
        if (updatedSource.success) renderSources();
        else setStatus(updatedSource.reason);
      });
    }
    if (removeButton) {
      removeButton.addEventListener("click", () => {
        const removalResult = onboarding.removeSource(source.id);
        if (removalResult.success) { renderSources(); setStatus("Source removed"); }
        else setStatus(removalResult.reason);
      });
    }
    return card;
  }
  function updateToggleButton(button, enabled) {
    if (!button) return;
    button.textContent = enabled ? "Disable" : "Enable";
    button.setAttribute("data-source-enabled", enabled ? "true" : "false");
  }
  function buildHealthText(sourceId) {
    const source = onboarding.getSource(sourceId);
    if (!source) return "Unavailable";
    const healthRecord = getStoredHealthRecord(sourceId);
    if (!healthRecord) return "No activity yet";
    const successes = healthRecord.successes || 0;
    const failures = healthRecord.failures || 0;
    const total = successes + failures;
    const successRate = total === 0 ? 0 : Math.round((successes / total) * 100);
    const latency = healthRecord.averageLatencyMs || 0;
    const coolingDown = healthRecord.cooldownUntil > Date.now();
    const parts = [`${successRate}% success`];
    if (latency > 0) parts.push(`${latency} ms`);
    if (coolingDown) parts.push("cooling down");
    return parts.join(" · ");
  }
  function getStoredHealthRecord(sourceId) {
    try {
      const rawHealth = localStorage.getItem("zeroplay.global.source-health.v1");
      if (!rawHealth) return null;
      const parsedHealth = JSON.parse(rawHealth);
      return parsedHealth[sourceId] || null;
    } catch (error) { return null; }
  }
  function formatSourceKind(kind) {
    const normalizedKind = String(kind || "").toLowerCase();
    if (normalizedKind === "addon-manifest") return "Addon";
    if (normalizedKind === "json-api") return "API";
    if (normalizedKind === "public-catalog") return "Catalog";
    if (normalizedKind === "html-page") return "Website";
    return "Source";
  }
  function renderSummary() {
    const summaryElement = getSummaryElement();
    if (!summaryElement) return;
    const statistics = onboarding.getStatistics();
    summaryElement.textContent = `${statistics.enabledSources} enabled · ${statistics.healthySources} healthy · ${statistics.coolingDownSources} cooling down`;
  }
  function setStatus(message) {
    const statusElement = getStatusElement();
    if (!statusElement) return;
    statusElement.textContent = message || "";
  }
  function showLoading(message) { setStatus(message || "Loading sources"); }
  function showError(message) { setStatus(message || "Source manager unavailable"); }
  function clear() {
    const listElement = getListElement();
    if (listElement) listElement.innerHTML = "";
    const summaryElement = getSummaryElement();
    if (summaryElement) summaryElement.textContent = "";
    setStatus("");
  }
}
module.exports = {
  createGlobalSourceManagerUi, SOURCE_MANAGER_CONTAINER_SELECTOR, SOURCE_LIST_SELECTOR, SOURCE_SUMMARY_SELECTOR, SOURCE_STATUS_SELECTOR
};
