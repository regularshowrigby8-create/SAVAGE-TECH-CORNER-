'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { once } = require('node:events');
const { publicHttpsUrl, normalizeIdentity, parseManifest, providerRecord, extractManifestUrls, fetchTextJson, crawlSource, findCandidates, runWorker } = require('../lib/server-finder/core');
const findApi = require('../api/find');
const { createLocalServer } = require('../server');

const manifest = {
  id: 'org.example.lola', name: 'Lola Stream Candidate', version: '1.0.0',
  description: 'Public test addon', resources: ['stream', 'catalog', 'meta'],
  types: ['movie', 'series'], idPrefixes: ['tt'],
  catalogs: [{ type: 'movie', id: 'search', extra: [{ name: 'search' }] }],
};
function response(data, status = 200, headers = {}) {
  return {
    ok: status >= 200 && status < 300, status,
    headers: { get(name) { return headers[String(name).toLowerCase()] || null; } },
    async text() { return typeof data === 'string' ? data : JSON.stringify(data); },
  };
}
function responseStub() {
  return { statusCode: 200, headers: {}, body: null, setHeader(name, value) { this.headers[name] = value; }, status(code) { this.statusCode = code; return this; }, json(value) { this.body = value; return value; } };
}

(async () => {
  assert.equal(publicHttpsUrl('https://addon.example/manifest.json'), 'https://addon.example/manifest.json', 'public HTTPS addon manifests are accepted');
  for (const url of ['http://addon.example/manifest.json', 'https://localhost/manifest.json', 'https://127.0.0.1/manifest.json', 'https://192.168.1.2/manifest.json', 'https://addon.local/manifest.json']) {
    assert.equal(publicHttpsUrl(url), '', 'private, local, and non-HTTPS targets are rejected');
  }
  const identity = normalizeIdentity({ title: 'My Heart Beats for Lola Volcán', titles: ['Mi corazón insiste en Lola Volcán'], type: 'tv', year: '2011', imdbId: 'tt1929755', tmdbId: '35281', season: 1, episode: 1 });
  assert.deepEqual(identity.titles, ['My Heart Beats for Lola Volcán', 'Mi corazón insiste en Lola Volcán'], 'identity retains English and original title variants');
  assert.equal(identity.imdbId, 'tt1929755');
  assert.throws(() => normalizeIdentity({ title: ' ' }), /title is required/i, 'empty title searches are rejected');
  assert.equal(parseManifest(manifest).resources.includes('stream'), true, 'stream-capable manifest is identified');
  assert.equal(parseManifest({ ...manifest, resources: ['catalog'] }), null, 'catalog-only manifests are not server candidates');
  assert.equal(parseManifest({ ...manifest, types: ['person'] }), null, 'non-video manifests are not server candidates');
  const record = providerRecord('https://addon.example/manifest.json', manifest, 'fixture');
  assert.equal(record.status, 'candidate', 'discovered providers remain candidates');
  assert.equal(record.titleScopedDiscovery, true, 'candidate is marked for title-scoped verification');
  assert.equal(record.identification.installable, false, 'manifest discovery alone cannot install a provider');
  assert.equal(record.identification.supports.streams, true, 'candidate stream capability is surfaced to the existing title scanner');
  assert.deepEqual(extractManifestUrls('Install at https://addons.example/manifest.json and not https://example.org/home'), ['https://addons.example/manifest.json'], 'GitHub README scraper only extracts manifest endpoints');

  let calls = 0;
  await assert.rejects(fetchTextJson('http://127.0.0.1/private', { fetchImpl: async () => { calls++; return response({}); } }), /Blocked URL/, 'unsafe requests are rejected before fetching');
  assert.equal(calls, 0, 'private URL rejection avoids network access');
  await assert.rejects(fetchTextJson('https://public.example/start', { fetchImpl: async () => response('', 302, { location: 'http://127.0.0.1/private' }) }), /Blocked redirect/, 'redirects to private targets are blocked');

  const fixtureFetch = async url => {
    const value = String(url);
    if (value.includes('stremio-addons.net/api/v0/addons')) return response({ addons: [
      { manifestUrl: 'https://addon.example/manifest.json', manifest, stars: 7 },
      { manifestUrl: 'http://unsafe.example/manifest.json', manifest },
      { manifestUrl: 'https://catalog-only.example/manifest.json', manifest: { ...manifest, resources: ['catalog'] } },
    ] });
    if (value.includes('addon_catalog/all/')) return response({ addons: [] });
    if (value.includes('api.github.com/search/repositories')) return response({ items: [] });
    throw new Error('Unexpected fixture request ' + value);
  };
  const catalog = await crawlSource('stremio-addons', identity, { fetchImpl: fixtureFetch, maxPerSource: 12, timeoutMs: 1000 });
  assert.equal(catalog.status, 'complete', 'catalog crawler completes with fixture data');
  assert.equal(catalog.candidates, 1, 'only a public HTTPS stream manifest becomes a candidate');
  assert.equal(catalog.records[0].source, 'Server Finder · stremio-addons', 'candidate provenance is retained');
  const partialGraph = await crawlSource('catalog-graph', identity, { fetchImpl: async url => String(url).includes('community.json') ? response({}, 503) : response({ addons: [] }), timeoutMs: 1000 });
  assert.equal(partialGraph.status, 'partial', 'one unavailable catalogue is reported as partial rather than silently complete');
  const failedGraph = await crawlSource('catalog-graph', identity, { fetchImpl: async () => { throw new Error('offline'); }, timeoutMs: 1000 });
  assert.equal(failedGraph.status, 'failed', 'all unavailable catalogues are reported as failed');
  const bounded = await findCandidates(identity, { sourceIds: ['stremio-addons'], fetchImpl: fixtureFetch, maxPerSource: 12, limit: 1, timeoutMs: 1000 });
  assert.equal(bounded.candidateCount, 1, 'candidate results obey caller limits');
  assert.equal(bounded.mode, 'hosted-bounded-pool', 'hosted mode uses bounded async workers');

  const threadResult = await runWorker('unknown-source', identity, { timeoutMs: 1000, workerTimeoutMs: 5000 });
  assert.equal(threadResult.status, 'failed', 'local worker_threads execute and report isolated source failures');
  assert.match(threadResult.error, /unknown crawler/i, 'worker failures are contained as source status rather than crashing the server');

  const originalFetch = global.fetch;
  global.fetch = async url => {
    const value = String(url);
    if (value.includes('stremio-addons.net/api/v0/addons')) return response({ addons: [] });
    if (value.includes('addon_catalog/all/')) return response({ addons: [] });
    if (value.includes('api.github.com/search/repositories')) return response({ items: [] });
    throw new Error('Unexpected API fixture request ' + value);
  };
  try {
    const badMethod = responseStub();
    await findApi({ method: 'DELETE', headers: {}, query: {} }, badMethod);
    assert.equal(badMethod.statusCode, 405, 'hosted endpoint is read-only apart from title search');
    const badOrigin = responseStub();
    await findApi({ method: 'GET', headers: { origin: 'https://evil.example', host: 'zeroplay.example' }, query: { q: 'Lola' } }, badOrigin);
    assert.equal(badOrigin.statusCode, 403, 'hosted endpoint rejects cross-origin requests');
    const hosted = responseStub();
    await findApi({ method: 'POST', headers: { host: 'zeroplay.example', origin: 'https://zeroplay.example', 'x-forwarded-for': '203.0.113.4' }, query: {}, body: { identity } }, hosted);
    assert.equal(hosted.statusCode, 200, 'hosted API returns candidate-discovery status');
    assert.equal(hosted.body.ok, true, 'hosted API returns a successful bounded search');
    assert.equal(hosted.body.candidateCount, 0, 'no manifests means no candidates are promoted');
  } finally { global.fetch = originalFetch; }

  const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
  assert(html.includes('fetch("/api/mcp"'), 'browser uses a same-origin MCP finder URL instead of hard-coded localhost');
  assert(html.includes('serverFinderProgressHTML') && html.includes('serverFinderSourceStatus'), 'finder source progress appears separately in the title-search UI');
  assert(html.includes('result.identityVerified>0') && html.includes('status:"installed"'), 'browser retains exact-match-only candidate promotion');

  const local = createLocalServer();
  local.listen(0, '127.0.0.1');
  await once(local, 'listening');
  try {
    const address = local.address();
    const health = await fetch('http://127.0.0.1:' + address.port + '/api/health');
    const status = await health.json();
    assert.equal(status.ok, true, 'local sidecar health endpoint responds');
    assert.equal(status.mode, 'local-worker-threads', 'local mode advertises its actual worker strategy');
    const home = await fetch('http://127.0.0.1:' + address.port + '/');
    assert.equal(home.status, 200, 'local server serves the existing browser app from the same origin');
    assert((await home.text()).includes('ZeroPlay'), 'local app shell is served without a localhost URL in browser code');
  } finally { await new Promise(resolve => local.close(resolve)); }

  console.log('PASS bounded local/hosted Server Finder, safe candidate discovery, same-origin fallback, and exact-title promotion guard');
})().catch(error => { console.error(error); process.exit(1); });
