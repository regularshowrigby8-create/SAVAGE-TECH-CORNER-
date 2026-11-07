'use strict';

const net = require('node:net');
const crypto = require('node:crypto');
const { Worker } = require('node:worker_threads');
const path = require('node:path');

const SOURCE_IDS = Object.freeze(['stremio-addons', 'catalog-graph', 'github']);
const MAX_CANDIDATES = 1000;
const MAX_PER_SOURCE = 8;
const MAX_MANIFEST_BYTES = 160000;
const MAX_JSON_BYTES = 1200000;
const DEFAULT_TIMEOUT_MS = 4500;
const DEFAULT_SOURCE_CONCURRENCY = 6;

function cleanText(value, max = 240) {
  return String(value || '').replace(/\s+/g, ' ').trim().slice(0, max);
}

function publicHttpsUrl(raw) {
  let url;
  try { url = new URL(String(raw || '')); } catch { return ''; }
  if (url.protocol !== 'https:' || !url.hostname || url.username || url.password) return '';
  const host = url.hostname.toLowerCase().replace(/^\[|\]$/g, '');
  if (net.isIP(host)) return '';
  if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || host.endsWith('.internal')) return '';
  return url.href.slice(0, 1800);
}

function normalizeIdentity(raw) {
  raw = raw && typeof raw === 'object' ? raw : {};
  const title = cleanText(raw.title, 160);
  if (title.length < 2) throw new Error('A title is required');
  const type = raw.type === 'tv' || raw.type === 'series' ? 'tv' : 'movie';
  const titles = [...new Set([title, ...(Array.isArray(raw.titles) ? raw.titles : [])]
    .map(value => cleanText(value, 160)).filter(value => value.length >= 2))].slice(0, 5);
  const year = /^\d{4}$/.test(String(raw.year || '')) ? Number(raw.year) : null;
  const imdbId = /^tt\d{5,12}$/i.test(String(raw.imdbId || '')) ? String(raw.imdbId).toLowerCase() : '';
  const tmdbId = /^[1-9]\d{0,9}$/.test(String(raw.tmdbId || '')) ? Number(raw.tmdbId) : null;
  const season = Number.isInteger(Number(raw.season)) && Number(raw.season) > 0 && Number(raw.season) <= 999 ? Number(raw.season) : 1;
  const episode = Number.isInteger(Number(raw.episode)) && Number(raw.episode) > 0 && Number(raw.episode) <= 9999 ? Number(raw.episode) : 1;
  return { title, titles, type, year, imdbId, tmdbId, season, episode };
}

function parseManifest(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const resourceRows = Array.isArray(raw.resources) ? raw.resources : [];
  const resources = [...new Set(resourceRows.map(item => typeof item === 'string' ? item : item && item.name).filter(Boolean).map(String))];
  if (!resources.includes('stream')) return null;
  const types = [...new Set((Array.isArray(raw.types) ? raw.types : []).map(value => String(value).toLowerCase()))];
  if (!types.some(type => ['movie', 'series', 'tv'].includes(type))) return null;
  const prefixes = [...new Set([
    ...(Array.isArray(raw.idPrefixes) ? raw.idPrefixes : []),
    ...resourceRows.flatMap(item => item && typeof item === 'object' && Array.isArray(item.idPrefixes) ? item.idPrefixes : []),
  ].map(value => String(value).toLowerCase()).filter(value => /^[a-z0-9_-]{1,24}$/.test(value)))];
  const catalogs = Array.isArray(raw.catalogs) ? raw.catalogs.filter(item => item && typeof item === 'object') : [];
  const searchCatalogs = catalogs.filter(catalog => Array.isArray(catalog.extra) && catalog.extra.some(extra => extra && extra.name === 'search'));
  const addonCatalogs = Array.isArray(raw.addonCatalogs) ? raw.addonCatalogs.filter(item => item && typeof item === 'object') : [];
  return {
    id: cleanText(raw.id, 120), name: cleanText(raw.name || raw.id || 'Streaming addon', 80),
    version: cleanText(raw.version, 40), description: cleanText(raw.description, 240),
    resources, types, idPrefixes: prefixes, catalogs: catalogs.slice(0, 30),
    catalogDefs: catalogs.slice(0, 30), searchCatalogs: searchCatalogs.slice(0, 30), addonCatalogs: addonCatalogs.slice(0, 30),
  };
}

function providerRecord(manifestUrl, manifest, source, meta = {}) {
  const safeManifest = publicHttpsUrl(manifestUrl);
  const info = parseManifest(manifest);
  if (!safeManifest || !info) return null;
  const base = safeManifest.replace(/\/manifest\.json(?:[?#].*)?$/i, '').replace(/\/$/, '');
  if (!base) return null;
  const id = 'addon:' + base.toLowerCase();
  const types = info.types.map(type => type === 'tv' ? 'series' : type);
  return {
    id, name: info.name, kind: 'stremio-addon', status: 'candidate', titleScopedDiscovery: true,
    enabled: true, base, endpoint: base, manifestUrl: safeManifest, manifestId: info.id,
    version: info.version, resources: info.resources, types, idPrefixes: info.idPrefixes,
    catalogDefs: info.catalogDefs, addonCatalogs: info.addonCatalogs, searchCatalogs: info.searchCatalogs,
    stars: Number(meta.stars) || 0, source: 'Server Finder · ' + source, from: 'Server Finder · ' + source,
    description: info.description || cleanText(meta.description, 240), homepage: publicHttpsUrl(meta.homepage) || '',
    tags: Array.isArray(meta.tags) ? meta.tags.map(value => cleanText(value, 60)).filter(Boolean).slice(0, 20) : [],
    identification: {
      version: 1, kind: 'stremio-addon', confidence: 88, identifiedAt: Date.now(), searchable: info.searchCatalogs.length > 0,
      supports: { movie: types.includes('movie'), series: types.includes('series'), streams: true, catalog: info.resources.includes('catalog'), meta: info.resources.includes('meta'), subtitles: info.resources.includes('subtitles'), addonCatalog: info.resources.includes('addon_catalog'), search: info.searchCatalogs.length > 0, episodeLookup: types.includes('series') },
      identifierModes: info.idPrefixes.map(prefix => prefix === 'tt' ? 'imdb-id' : prefix), idPrefixes: info.idPrefixes,
      checks: ['public HTTPS manifest', 'stream resource and movie/series types confirmed', 'candidate requires title-scoped identity-verified playback match'],
      installable: false, playableStream: false,
    },
    lastCheckedAt: Date.now(), lastError: '',
  };
}

function extractManifestUrls(text) {
  const urls = [];
  const seen = new Set();
  const pattern = /https?:\/\/[^\s"'<>()[\]{}]+/gi;
  for (const match of String(text || '').matchAll(pattern)) {
    let raw = match[0].replace(/[.,;!?]+$/, '').replace(/&amp;/g, '&');
    if (!/manifest\.json(?:[?#].*)?$/i.test(raw)) continue;
    const url = publicHttpsUrl(raw);
    if (url && !seen.has(url)) { urls.push(url); seen.add(url); }
    if (urls.length >= 12) break;
  }
  return urls;
}

async function fetchTextJson(url, options = {}) {
  const fetchImpl = options.fetchImpl || globalThis.fetch;
  const timeoutMs = options.timeoutMs || DEFAULT_TIMEOUT_MS;
  if (typeof fetchImpl !== 'function') throw new Error('Fetch is unavailable');
  let next = publicHttpsUrl(url);
  if (!next) throw new Error('Blocked URL');
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    for (let hop = 0; hop <= 3; hop++) {
      const response = await fetchImpl(next, {
        signal: controller.signal, redirect: 'manual',
        headers: { Accept: 'application/json,text/plain;q=0.9,*/*;q=0.8', 'User-Agent': 'ZeroPlay-Server-Finder/1.0' },
      });
      if (response.status >= 300 && response.status < 400) {
        if (hop === 3) throw new Error('Too many redirects');
        const location = response.headers && response.headers.get && response.headers.get('location');
        const redirected = publicHttpsUrl(new URL(location || '', next).href);
        if (!redirected) throw new Error('Blocked redirect');
        next = redirected;
        continue;
      }
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const text = await response.text();
      const byteLength = Buffer.byteLength(text, 'utf8');
      if (byteLength > (options.maxBytes || MAX_JSON_BYTES)) throw new Error('Response too large');
      return { data: options.allowText ? text : JSON.parse(text), url: next };
    }
    throw new Error('Too many redirects');
  } finally { clearTimeout(timer); }
}

function mapLimit(items, limit, fn) {
  const list = Array.isArray(items) ? items : [];
  let cursor = 0;
  const workers = Array.from({ length: Math.max(1, Math.min(limit, list.length || 1)) }, async () => {
    while (cursor < list.length) {
      const item = list[cursor++];
      try { await fn(item); } catch {}
    }
  });
  return Promise.all(workers);
}

function entryUrl(entry) {
  return publicHttpsUrl(entry && (entry.manifestUrl || entry.transportUrl || entry.transport && entry.transport.url || entry.url) || '');
}

async function crawlStremioAddons(identity, options) {
  const { data } = await fetchTextJson('https://stremio-addons.net/api/v0/addons?limit=100&nsfw=exclude&sort_by=stars&order=desc&page=1', options);
  const entries = [];
  for (const addon of Array.isArray(data.addons) ? data.addons : []) {
    entries.push(addon);
    for (const instance of Array.isArray(addon.instances) ? addon.instances : []) entries.push(instance);
  }
  return { entries: entries.slice(0, 100), seen: entries.length };
}

async function crawlCatalogGraph(identity, options) {
  const endpoints = [
    'https://v3-cinemeta.strem.io/addon_catalog/all/community.json',
    'https://v3-cinemeta.strem.io/addon_catalog/all/official.json',
  ];
  const entries = [];
  const errors = [];
  let seen = 0;
  await mapLimit(endpoints, 2, async endpoint => {
    try {
      const { data } = await fetchTextJson(endpoint, options);
      for (const addon of Array.isArray(data.addons) ? data.addons : []) { entries.push(addon); seen++; }
    } catch (error) { errors.push(cleanText(error && error.message || 'Catalogue unavailable', 120)); }
  });
  if (errors.length === endpoints.length) throw new Error(errors[0] || 'Catalogues unavailable');
  return { entries: entries.slice(0, 100), seen, errors };
}

async function crawlGitHub(identity, options) {
  const query = 'topic:stremio-addon';
  const { data } = await fetchTextJson('https://api.github.com/search/repositories?q=' + encodeURIComponent(query) + '&sort=updated&order=desc&per_page=30', options);
  const repos = Array.isArray(data.items) ? data.items : [];
  const entries = [];
  let readmes = 0;
  await mapLimit(repos.slice(0, 12), 4, async repo => {
    const repoUrl = publicHttpsUrl(repo.homepage || '');
    const description = cleanText(repo.description, 240);
    const metaText = [description, repoUrl].join(' ');
    const inlineUrls = extractManifestUrls(metaText);
    for (const manifestUrl of inlineUrls) entries.push({ manifestUrl, stars: repo.stargazers_count, description, homepage: repoUrl, tags: repo.topics || [], name: repo.name });
    if (inlineUrls.length) return;
    const fullName = String(repo.full_name || '');
    if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(fullName) || readmes >= 4) return;
    readmes++;
    const branch = String(repo.default_branch || 'main').replace(/[^A-Za-z0-9_.-]/g, '').slice(0, 80) || 'main';
    try {
      const readme = await fetchTextJson('https://raw.githubusercontent.com/' + fullName + '/' + branch + '/README.md', { ...options, allowText: true });
      for (const manifestUrl of extractManifestUrls(typeof readme.data === 'string' ? readme.data : '')) entries.push({ manifestUrl, stars: repo.stargazers_count, description, homepage: repoUrl, tags: repo.topics || [], name: repo.name, repoUrl: 'https://github.com/' + fullName });
    } catch {}
    if (repo.has_pages && repo.owner && repo.name) {
      const githubPages = publicHttpsUrl('https://' + repo.owner.login + '.github.io/' + repo.name + '/manifest.json');
      if (githubPages) entries.push({ manifestUrl: githubPages, stars: repo.stargazers_count, description, tags: repo.topics || [], name: repo.name, repoUrl: 'https://github.com/' + fullName });
    }
  });
  return { entries: entries.slice(0, 100), seen: repos.length };
}

const sourceFunctions = Object.freeze({
  'stremio-addons': crawlStremioAddons,
  'catalog-graph': crawlCatalogGraph,
  github: crawlGitHub,
});

async function identifyEntries(sourceId, entries, options = {}) {
  const candidates = [];
  const seen = new Set();
  const manifestQueue = [];
  for (const entry of entries || []) {
    const manifestUrl = entryUrl(entry);
    if (!manifestUrl || seen.has(manifestUrl)) continue;
    seen.add(manifestUrl);
    manifestQueue.push({ entry, manifestUrl });
    if (manifestQueue.length >= (options.maxPerSource || MAX_PER_SOURCE)) break;
  }
  await mapLimit(manifestQueue, options.manifestConcurrency || DEFAULT_SOURCE_CONCURRENCY, async ({ entry, manifestUrl }) => {
    try {
      const { data: manifest } = entry && entry.manifest && typeof entry.manifest === 'object'
        ? { data: entry.manifest }
        : await fetchTextJson(manifestUrl, { ...options, maxBytes: MAX_MANIFEST_BYTES });
      const record = providerRecord(manifestUrl, manifest, sourceId, entry || {});
      if (record) candidates.push(record);
    } catch {}
  });
  return { candidates, seen: manifestQueue.length };
}

async function crawlSource(sourceId, identity, options = {}) {
  const crawl = sourceFunctions[sourceId];
  if (!crawl) throw new Error('Unknown crawler source');
  const started = Date.now();
  try {
    const discovered = await crawl(identity, options);
    const identified = await identifyEntries(sourceId, discovered.entries, options);
    const partial = Array.isArray(discovered.errors) && discovered.errors.length > 0;
    return {
      id: sourceId, status: partial ? 'partial' : 'complete', seen: discovered.seen || 0, checked: identified.seen,
      candidates: identified.candidates.length, records: identified.candidates,
      ...(partial ? { error: discovered.errors.length + ' catalogue endpoint(s) unavailable' } : {}),
      elapsedMs: Date.now() - started,
    };
  } catch (error) {
    return {
      id: sourceId, status: 'failed', seen: 0, checked: 0, candidates: 0, records: [],
      error: cleanText(error && error.message || 'Source unavailable', 140), elapsedMs: Date.now() - started,
    };
  }
}

function stableFingerprint(identity) {
  return crypto.createHash('sha256').update(JSON.stringify(identity)).digest('hex').slice(0, 20);
}

function runWorker(sourceId, identity, options) {
  return new Promise(resolve => {
    const worker = new Worker(path.join(__dirname, 'worker.js'), {
      workerData: { sourceId, identity, options: { timeoutMs: options.timeoutMs, maxPerSource: options.maxPerSource } },
    });
    const timer = setTimeout(() => {
      worker.terminate().catch(() => {});
      resolve({ id: sourceId, status: 'failed', seen: 0, checked: 0, candidates: 0, records: [], error: 'Worker timed out', elapsedMs: options.timeoutMs || DEFAULT_TIMEOUT_MS });
    }, options.workerTimeoutMs || 45000);
    worker.once('message', result => { clearTimeout(timer); resolve(result); });
    worker.once('error', error => {
      clearTimeout(timer);
      resolve({ id: sourceId, status: 'failed', seen: 0, checked: 0, candidates: 0, records: [], error: cleanText(error.message, 140), elapsedMs: 0 });
    });
    worker.once('exit', code => {
      if (code !== 0) {
        clearTimeout(timer);
        resolve({ id: sourceId, status: 'failed', seen: 0, checked: 0, candidates: 0, records: [], error: 'Worker exited', elapsedMs: 0 });
      }
    });
  });
}

async function findCandidates(rawIdentity, options = {}) {
  let identity;
  try { identity = normalizeIdentity(rawIdentity); }
  catch (error) { return { ok: false, error: cleanText(error.message, 120), candidates: [], sources: [], fingerprint: '' }; }
  const sourceIds = [...new Set((Array.isArray(options.sourceIds) ? options.sourceIds : SOURCE_IDS).filter(id => sourceFunctions[id]))];
  const limit = Math.max(0, Math.min(MAX_CANDIDATES, Number(options.limit) || MAX_CANDIDATES));
  const common = { timeoutMs: Math.max(1000, Math.min(12000, Number(options.timeoutMs) || DEFAULT_TIMEOUT_MS)), maxPerSource: Math.max(1, Math.min(MAX_PER_SOURCE, Number(options.maxPerSource) || MAX_PER_SOURCE)), fetchImpl: options.fetchImpl };
  let sources;
  if (options.useWorkerThreads && !options.fetchImpl) {
    sources = await Promise.all(sourceIds.map(id => runWorker(id, identity, { ...common, workerTimeoutMs: options.workerTimeoutMs })));
  } else {
    sources = await Promise.all(sourceIds.map(id => crawlSource(id, identity, common)));
  }
  const candidates = [];
  const seen = new Set();
  for (const source of sources) {
    for (const record of source.records || []) {
      if (seen.has(record.manifestUrl)) continue;
      seen.add(record.manifestUrl);
      candidates.push(record);
      if (candidates.length >= limit) break;
    }
    if (candidates.length >= limit) break;
  }
  const complete = sources.filter(source => source.status !== 'failed').length;
  return {
    ok: true, identity, fingerprint: stableFingerprint(identity),
    mode: options.useWorkerThreads ? 'local-worker-threads' : 'hosted-bounded-pool',
    sourcesTotal: sources.length, sourcesComplete: complete, sourcesFailed: sources.length - complete,
    candidates, candidateCount: candidates.length, truncated: candidates.length >= limit,
    workers: options.useWorkerThreads ? sources.length : Math.min(sources.length, options.sourceConcurrency || 8),
    sources: sources.map(({ records, ...source }) => source),
  };
}

module.exports = {
  SOURCE_IDS, MAX_CANDIDATES, MAX_PER_SOURCE, publicHttpsUrl, normalizeIdentity,
  parseManifest, providerRecord, extractManifestUrls, fetchTextJson, identifyEntries,
  crawlSource, findCandidates, runWorker,
};
