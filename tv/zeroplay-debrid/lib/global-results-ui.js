"use strict";
const GLOBAL_RESULT_KINDS = { DIRECT_MEDIA: "direct-media", PLAYLIST: "playlist", MANIFEST: "manifest", CANDIDATE: "candidate" };
const GLOBAL_BADGE_CLASS = "zp-global-badge";
const GLOBAL_META_CLASS = "zp-global-meta";
const GLOBAL_ACTION_CLASS = "zp-global-action";
const GLOBAL_STATUS_CLASS = "zp-global-status";
const GLOBAL_CARD_ATTRIBUTE = "data-global-result-id";
function createGlobalResultsUi(options) {
  const config = options || {};
  const playerSelector = config.playerSelector || "[data-player]";
  const resultsContainerSelector = config.resultsContainerSelector || "[data-results]";
  const statusSelector = config.statusSelector || "[data-global-status]";
  const playbackResults = new Map();
  return {
    renderGlobalResults, updateGlobalStatus, showGlobalLoading, showGlobalEmpty, showGlobalError,
    clearGlobalResults, playGlobalResult, getPlaybackResults, clearPlaybackResults
  };
  function renderGlobalResults(results, context) {
    const container = document.querySelector(resultsContainerSelector);
    if (!container) return { rendered: 0, reason: "results-container-not-found" };
    const normalizedResults = normalizeResultList(results);
    let renderedCount = 0;
    for (const result of normalizedResults) {
      const card = findOrCreateGlobalCard(container, result);
      if (!card) continue;
      updateGlobalCard(card, result);
      renderedCount += 1;
    }
    return { rendered: renderedCount, total: normalizedResults.length };
  }
  function findOrCreateGlobalCard(container, result) {
    const existingCard = container.querySelector(`[${GLOBAL_CARD_ATTRIBUTE}="${result.id}"]`);
    if (existingCard) return existingCard;
    const card = document.createElement("div");
    card.className = "zp-global-result-card";
    card.setAttribute(GLOBAL_CARD_ATTRIBUTE, result.id);
    card.innerHTML = `
      <div class="zp-global-result-header">
        <span class="${GLOBAL_BADGE_CLASS}">Global</span>
        <span class="zp-global-result-title"></span>
      </div>
      <div class="${GLOBAL_META_CLASS}"></div>
      <div class="${GLOBAL_STATUS_CLASS}"></div>
      <div class="zp-global-result-actions">
        <button type="button" class="${GLOBAL_ACTION_CLASS}">Play</button>
      </div>
    `;
    const actionButton = card.querySelector(`.${GLOBAL_ACTION_CLASS}`);
    if (actionButton) actionButton.addEventListener("click", () => playGlobalResult(result.id));
    container.appendChild(card);
    return card;
  }
  function updateGlobalCard(card, result) {
    if (!card || !result) return;
    const titleElement = card.querySelector(".zp-global-result-title");
    if (titleElement) titleElement.textContent = result.title || "Untitled result";
    const metaElement = card.querySelector(`.${GLOBAL_META_CLASS}`);
    if (metaElement) {
      metaElement.innerHTML = "";
      for (const metadataItem of buildMetadataItems(result)) {
        const chip = document.createElement("span");
        chip.className = "zp-global-meta-chip";
        chip.textContent = metadataItem;
        metaElement.appendChild(chip);
      }
    }
    const statusElement = card.querySelector(`.${GLOBAL_STATUS_CLASS}`);
    if (statusElement) statusElement.textContent = result.verified ? "Verified" : result.readyToUse ? "Ready" : "Needs verification";
    const actionButton = card.querySelector(`.${GLOBAL_ACTION_CLASS}`);
    if (actionButton) {
      actionButton.disabled = !result.readyToUse;
      actionButton.textContent = result.readyToUse ? "Play" : "Unavailable";
    }
    playbackResults.set(result.id, result);
  }
  function buildMetadataItems(result) {
    const items = [];
    if (result.quality) items.push(result.quality);
    if (result.size) items.push(result.size);
    if (result.codec) items.push(result.codec);
    if (result.language) items.push(result.language);
    if (result.sourceName) items.push(result.sourceName);
    if (result.resultKind) items.push(formatResultKind(result.resultKind));
    return items.slice(0, 5);
  }
  function formatResultKind(resultKind) {
    if (resultKind === GLOBAL_RESULT_KINDS.DIRECT_MEDIA) return "Direct";
    if (resultKind === GLOBAL_RESULT_KINDS.PLAYLIST) return "HLS";
    if (resultKind === GLOBAL_RESULT_KINDS.MANIFEST) return "DASH";
    return "Link";
  }
  function updateGlobalStatus(status) {
    const statusElement = document.querySelector(statusSelector);
    if (!statusElement) return;
    statusElement.textContent = status || "Idle";
  }
  function showGlobalLoading(message) { updateGlobalStatus(message || "Searching globally"); }
  function showGlobalEmpty(message) { updateGlobalStatus(message || "No global results found"); }
  function showGlobalError(message) { updateGlobalStatus(message || "Global search unavailable"); }
  function clearGlobalResults() {
    const cards = document.querySelectorAll(`[${GLOBAL_CARD_ATTRIBUTE}]`);
    for (const card of cards) card.remove();
    playbackResults.clear();
  }
  async function playGlobalResult(resultId) {
    const result = playbackResults.get(resultId);
    if (!result) return { success: false, reason: "result-not-found" };
    if (!result.readyToUse || !result.url) return { success: false, reason: "result-not-ready" };
    const player = document.querySelector(playerSelector);
    if (!player) return { success: false, reason: "player-not-found" };
    const playbackToken = createPlaybackToken();
    player.dataset.activePlaybackToken = playbackToken;
    player.dataset.playbackUrl = result.url;
    player.dataset.playbackKind = result.resultKind;
    player.dataset.playbackSource = result.sourceName || "global";
    player.dispatchEvent(new CustomEvent("zp:global-playback", { detail: { result, playbackUrl: result.url, playbackToken } }));
    return { success: true, playbackUrl: result.url, playbackToken, result };
  }
  function getPlaybackResults() { return [...playbackResults.values()]; }
  function clearPlaybackResults() { playbackResults.clear(); }
}
function normalizeResultList(results) {
  if (!Array.isArray(results)) return [];
  return results.map(normalizeResult).filter(Boolean);
}
function normalizeResult(result) {
  if (!result) return null;
  const url = result.url || result.href || result.link || "";
  if (!url) return null;
  return {
    id: result.id || createStableId(url), url, title: result.title || result.name || "Untitled result",
    sourceId: result.sourceId || "", sourceName: result.sourceName || "", sourceKind: result.sourceKind || "",
    resultKind: result.resultKind || result.kind || GLOBAL_RESULT_KINDS.DIRECT_MEDIA, origin: result.origin || "global",
    quality: result.quality || "", size: result.size || "", codec: result.codec || "", language: result.language || "",
    verified: result.verified === true, readyToUse: result.readyToUse !== false,
    identityScore: result.identityScore || result.relevanceScore || 0, discoveredAt: result.discoveredAt || Date.now()
  };
}
function createPlaybackToken() { return `playback-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`; }
function createStableId(input) {
  const crypto = require("node:crypto");
  return crypto.createHash("sha256").update(String(input)).digest("hex").slice(0, 32);
}
module.exports = {
  createGlobalResultsUi, GLOBAL_RESULT_KINDS, GLOBAL_BADGE_CLASS, GLOBAL_META_CLASS, GLOBAL_ACTION_CLASS, GLOBAL_STATUS_CLASS
};
