"use strict";
const TITLE_STOP_WORDS = new Set(["the","a","an","and","or","of","in","on","at","to","for","with","from","by","part","movie","film","series","tv","show","episode","season","watch","online","free","full","hd","stream","streaming"]);
const SEASON_EPISODE_PATTERNS = [
  { pattern: /\bs(\d{1,3})\s*[e|x]\s*(\d{1,4})\b/i, seasonGroup: 1, episodeGroup: 2, weight: 30 },
  { pattern: /\bseason\s*(\d{1,3})\s*episode\s*(\d{1,4})\b/i, seasonGroup: 1, episodeGroup: 2, weight: 30 },
  { pattern: /\b(\d{1,3})x(\d{1,4})\b/, seasonGroup: 1, episodeGroup: 2, weight: 28 },
  { pattern: /\bs(\d{1,3})\b/i, seasonGroup: 1, episodeGroup: null, weight: 12 },
  { pattern: /\bseason\s*(\d{1,3})\b/i, seasonGroup: 1, episodeGroup: null, weight: 12 },
  { pattern: /\be(pisode)?\s*(\d{1,4})\b/i, seasonGroup: null, episodeGroup: 2, weight: 14 }
];
const YEAR_PATTERN = /\b(19\d{2}|20\d{2})\b/;
function normalizeTitle(input) {
  if (!input) return "";
  return String(input).toLowerCase().normalize("NFKD").replace(/[\u0300-\u036f]/g, "").replace(/[^a-z0-9]+/g, " ").replace(/\s+/g, " ").trim();
}
function getTitleTokens(input) {
  const normalizedTitle = normalizeTitle(input);
  if (!normalizedTitle) return [];
  return normalizedTitle.split(" ").filter(Boolean);
}
function calculateTitleSimilarity(firstTitle, secondTitle) {
  const first = normalizeTitle(firstTitle);
  const second = normalizeTitle(secondTitle);
  if (!first || !second) return 0;
  if (first === second) return 1;
  const firstTokens = new Set(getTitleTokens(first));
  const secondTokens = new Set(getTitleTokens(second));
  if (firstTokens.size === 0 || secondTokens.size === 0) return 0;
  let intersectionCount = 0;
  for (const token of firstTokens) if (secondTokens.has(token)) intersectionCount += 1;
  const unionCount = firstTokens.size + secondTokens.size - intersectionCount;
  if (unionCount === 0) return 0;
  const jaccardScore = intersectionCount / unionCount;
  const containmentScore = intersectionCount / Math.min(firstTokens.size, secondTokens.size);
  return Math.max(jaccardScore, containmentScore * 0.9);
}
function extractYear(input) {
  if (!input) return null;
  const match = String(input).match(YEAR_PATTERN);
  if (!match) return null;
  const year = Number(match[1]);
  if (year < 1888 || year > 2100) return null;
  return year;
}
function extractSeasonEpisode(input) {
  if (!input) return { season: null, episode: null, confidence: 0 };
  const text = String(input).toLowerCase();
  let bestMatch = null;
  for (const pattern of SEASON_EPISODE_PATTERNS) {
    const match = text.match(pattern.pattern);
    if (!match) continue;
    const season = pattern.seasonGroup ? Number(match[pattern.seasonGroup]) : null;
    const episode = pattern.episodeGroup ? Number(match[pattern.episodeGroup]) : null;
    const confidence = pattern.weight;
    if (!bestMatch || confidence > bestMatch.confidence) bestMatch = { season, episode, confidence };
  }
  return bestMatch || { season: null, episode: null, confidence: 0 };
}
function isSameIdentifier(firstValue, secondValue) {
  if (!firstValue || !secondValue) return false;
  return String(firstValue).toLowerCase().trim() === String(secondValue).toLowerCase().trim();
}
function normalizeCandidate(candidate) {
  const input = candidate || {};
  return {
    title: input.title || input.name || "",
    originalTitle: input.originalTitle || input.original_title || "",
    aliases: Array.isArray(input.aliases) ? input.aliases : [],
    imdbId: input.imdbId || input.imdb_id || input.imdb || "",
    tmdbId: input.tmdbId || input.tmdb_id || input.tmdb || "",
    year: input.year || input.releaseYear || input.release_year || "",
    type: normalizeType(input.type || input.mediaType),
    url: input.url || input.href || "",
    text: input.text || input.description || input.snippet || ""
  };
}
function normalizeIdentity(identity) {
  const input = identity || {};
  return {
    title: input.title || input.name || "",
    originalTitle: input.originalTitle || input.original_title || "",
    aliases: Array.isArray(input.aliases) ? input.aliases : [],
    imdbId: input.imdbId || input.imdb_id || input.imdb || "",
    tmdbId: input.tmdbId || input.tmdb_id || input.tmdb || "",
    year: input.year || input.releaseYear || input.release_year || "",
    type: normalizeType(input.type || input.mediaType),
    season: input.season !== undefined && input.season !== null ? Number(input.season) : null,
    episode: input.episode !== undefined && input.episode !== null ? Number(input.episode) : null
  };
}
function normalizeType(input) {
  const value = String(input || "").toLowerCase().trim();
  if (value === "movie" || value === "film") return "movie";
  if (value === "series" || value === "tv" || value === "show") return "series";
  return "";
}
function calculateIdentityScore(candidate, identity) {
  const normalizedCandidate = normalizeCandidate(candidate);
  const normalizedIdentity = normalizeIdentity(identity);
  const breakdown = { title: 0, originalTitle: 0, aliases: 0, imdbId: 0, tmdbId: 0, year: 0, type: 0, season: 0, episode: 0, urlIdentity: 0 };
  const rejectionReasons = [];
  if (normalizedIdentity.imdbId && normalizedCandidate.imdbId) {
    if (isSameIdentifier(normalizedCandidate.imdbId, normalizedIdentity.imdbId)) breakdown.imdbId = 30;
    else rejectionReasons.push("imdb-id-mismatch");
  }
  if (normalizedIdentity.tmdbId && normalizedCandidate.tmdbId) {
    if (isSameIdentifier(normalizedCandidate.tmdbId, normalizedIdentity.tmdbId)) breakdown.tmdbId = 26;
    else rejectionReasons.push("tmdb-id-mismatch");
  }
  const titleSimilarity = calculateTitleSimilarity(normalizedCandidate.title, normalizedIdentity.title);
  breakdown.title = Math.round(titleSimilarity * 26);
  if (normalizedIdentity.originalTitle && normalizedCandidate.originalTitle) {
    breakdown.originalTitle = Math.round(calculateTitleSimilarity(normalizedCandidate.originalTitle, normalizedIdentity.originalTitle) * 10);
  }
  if (Array.isArray(normalizedIdentity.aliases) && normalizedIdentity.aliases.length > 0) {
    let bestAliasScore = 0;
    for (const alias of normalizedIdentity.aliases) {
      bestAliasScore = Math.max(bestAliasScore, calculateTitleSimilarity(normalizedCandidate.title, alias));
    }
    breakdown.aliases = Math.round(bestAliasScore * 8);
  }
  const candidateYear = extractYear(normalizedCandidate.year || normalizedCandidate.url || normalizedCandidate.text);
  const identityYear = extractYear(normalizedIdentity.year);
  if (candidateYear && identityYear) {
    const yearDifference = Math.abs(candidateYear - identityYear);
    if (yearDifference === 0) breakdown.year = 10;
    else if (yearDifference === 1) breakdown.year = 5;
    else rejectionReasons.push("year-mismatch");
  }
  if (normalizedIdentity.type && normalizedCandidate.type) {
    if (normalizedCandidate.type === normalizedIdentity.type) breakdown.type = 6;
    else rejectionReasons.push("type-mismatch");
  }
  const candidateSeasonEpisode = extractSeasonEpisode(normalizedCandidate.url || normalizedCandidate.title || normalizedCandidate.text);
  if (normalizedIdentity.type === "series") {
    if (normalizedIdentity.season && candidateSeasonEpisode.season) {
      if (candidateSeasonEpisode.season === normalizedIdentity.season) breakdown.season = 8;
      else rejectionReasons.push("season-mismatch");
    }
    if (normalizedIdentity.episode && candidateSeasonEpisode.episode) {
      if (candidateSeasonEpisode.episode === normalizedIdentity.episode) breakdown.episode = 10;
      else rejectionReasons.push("episode-mismatch");
    }
  }
  const identityText = buildIdentityText(normalizedIdentity);
  const candidateText = buildCandidateText(normalizedCandidate);
  if (identityText && candidateText) {
    const identityTokens = getTitleTokens(identityText);
    let matchedTokens = 0;
    for (const token of identityTokens) if (candidateText.includes(token) && token.length > 2) matchedTokens += 1;
    if (identityTokens.length > 0) breakdown.urlIdentity = Math.round((matchedTokens / identityTokens.length) * 12);
  }
  const rawScore = breakdown.title + breakdown.originalTitle + breakdown.aliases + breakdown.imdbId + breakdown.tmdbId + breakdown.year + breakdown.type + breakdown.season + breakdown.episode + breakdown.urlIdentity;
  const score = clampScore(rawScore);
  const hasStrongTitleMatch = titleSimilarity >= 0.72;
  const hasIdentifierMatch = breakdown.imdbId > 0 || breakdown.tmdbId > 0;
  const hasSeasonEpisodeMatch = normalizedIdentity.type !== "series" || ((!normalizedIdentity.season || breakdown.season > 0) && (!normalizedIdentity.episode || breakdown.episode > 0));
  const accepted = score >= 45 && (hasStrongTitleMatch || hasIdentifierMatch) && hasSeasonEpisodeMatch && rejectionReasons.length === 0;
  return {
    accepted, score, breakdown, rejectionReasons, titleSimilarity,
    candidateSeason: candidateSeasonEpisode.season, candidateEpisode: candidateSeasonEpisode.episode, candidateYear
  };
}
function buildIdentityText(identity) {
  return [identity.title, identity.originalTitle, identity.imdbId, identity.tmdbId, identity.year].filter(Boolean).join(" ").toLowerCase();
}
function buildCandidateText(candidate) {
  return [candidate.title, candidate.originalTitle, candidate.imdbId, candidate.tmdbId, candidate.year, candidate.url, candidate.text].filter(Boolean).join(" ").toLowerCase();
}
function clampScore(score) {
  if (!Number.isFinite(score)) return 0;
  return Math.max(0, Math.min(100, Math.round(score)));
}
function createIdentityContext(identity) {
  const normalizedIdentity = normalizeIdentity(identity);
  return {
    identity: normalizedIdentity, title: normalizedIdentity.title, originalTitle: normalizedIdentity.originalTitle,
    aliases: normalizedIdentity.aliases, imdbId: normalizedIdentity.imdbId, tmdbId: normalizedIdentity.tmdbId,
    year: normalizedIdentity.year, type: normalizedIdentity.type, season: normalizedIdentity.season, episode: normalizedIdentity.episode,
    normalizedTitle: normalizeTitle(normalizedIdentity.title), titleTokens: getTitleTokens(normalizedIdentity.title),
    identityText: buildIdentityText(normalizedIdentity)
  };
}
function scoreCandidate(candidate, identityContext) {
  const normalizedCandidate = normalizeCandidate(candidate);
  const identity = identityContext?.identity || normalizeIdentity(identityContext);
  const identityResult = calculateIdentityScore(normalizedCandidate, identity);
  return {
    accepted: identityResult.accepted, identityScore: identityResult.score, identityBreakdown: identityResult.breakdown,
    rejectionReasons: identityResult.rejectionReasons,
    candidate: { ...normalizedCandidate, identityScore: identityResult.score, accepted: identityResult.accepted }
  };
}
function rankCandidates(candidates, identity) {
  if (!Array.isArray(candidates)) return [];
  const identityContext = createIdentityContext(identity);
  const scoredCandidates = [];
  for (const candidate of candidates) {
    const scoredCandidate = scoreCandidate(candidate, identityContext);
    if (!scoredCandidate.accepted) continue;
    scoredCandidates.push(scoredCandidate);
  }
  scoredCandidates.sort((a, b) => b.identityScore - a.identityScore);
  return scoredCandidates;
}
module.exports = {
  normalizeTitle, getTitleTokens, calculateTitleSimilarity, extractYear, extractSeasonEpisode,
  calculateIdentityScore, createIdentityContext, scoreCandidate, rankCandidates
};
