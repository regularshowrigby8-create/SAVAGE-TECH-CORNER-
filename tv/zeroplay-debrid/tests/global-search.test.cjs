const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const api = require('../api/global-search.js');
const { ENGINE_IDS, runEngine, normRows, externalUrl } = api._test;
const enginesDir = path.join(__dirname, '..', 'api/vendor/cdrake-se/Engines');

function responseStub() {
  return {
    statusCode: 200, headers: {}, body: null,
    setHeader(name, value) { this.headers[name] = value; },
    status(code) { this.statusCode = code; return this; },
    json(body) { this.body = body; return body; },
  };
}

(async () => {
  assert.strictEqual(ENGINE_IDS.length, 11, 'ten GitHub-imported engine adapters plus one niche-title pass are registered');
  for (const engine of ['Aol', 'Ask', 'Bing', 'Google', 'Qwant', 'Suggest', 'Wikipedia', 'Yahoo', 'Yep', 'Youtube']) {
    assert(fs.existsSync(path.join(enginesDir, engine + '.js')), engine + ' adapter is vendored from the inspected GitHub source');
  }
  const upstream = fs.readFileSync(path.join(__dirname, '..', 'api/vendor/cdrake-se/UPSTREAM_COMMIT'), 'utf8').trim();
  assert.strictEqual(upstream, 'db68decc20f1fa4c164d1c35df971c8670a56c79', 'vendored upstream is pinned to a reviewed commit');
  assert(fs.readFileSync(path.join(__dirname, '..', 'api/vendor/cdrake-se/LICENSE'), 'utf8').includes('MIT'), 'upstream MIT license is retained');

  assert.strictEqual(externalUrl('http://example.com/watch'), '', 'global leads reject non-HTTPS pages');
  assert.strictEqual(externalUrl('https://127.0.0.1/private'), '', 'global leads reject loopback destinations');
  assert.strictEqual(externalUrl('example.com/watch'), 'https://example.com/watch', 'bare public result hosts normalize to HTTPS');
  const normalized = normRows('google', { Results: [{ Title: 'Exact title', Link: 'https://example.com/title', Description: '2011 series' }] });
  assert.strictEqual(normalized.length, 1, 'search results normalize from upstream schema');
  assert.strictEqual(normalized[0].href, 'https://example.com/title', 'normalized result keeps its safe URL');

  const Aol = require('../api/vendor/cdrake-se/Engines/Aol');
  const oldAolSearch = Aol.prototype.Search;
  Aol.prototype.Search = async function () {
    assert.strictEqual(this.Query, encodeURIComponent('Reference title 2011'), 'engine query is URL-encoded before calling upstream scraper');
    return { Results: [{ Title: 'Reference title 2011', Link: 'https://example.com/reference', Description: 'Exact result' }] };
  };
  try {
    const found = await runEngine('aol', 'Reference title 2011', 'en-US');
    assert(found.ok && found.results.length === 1, 'one engine failure boundary returns normalized results');
    assert.strictEqual(found.results[0].engine, 'aol', 'results retain their engine provenance');
  } finally { Aol.prototype.Search = oldAolSearch; }

  const Google = require('../api/vendor/cdrake-se/Engines/Google');
  const wikipediaApi = require('wikipedia');
  const oldGoogleSearch = Google.prototype.Search;
  const oldWikiSetLang = wikipediaApi.setLang;
  const oldWikiSearch = wikipediaApi.search;
  Google.prototype.Search = async function () { return { Results: [{ Title: 'Spanish original title', Link: 'https://example.com/original' }] }; };
  wikipediaApi.setLang = async () => {};
  wikipediaApi.search = async () => ({ results: [{ title: 'Original title', pageid: 42 }] });
  try {
    const niche = await runEngine('niche', 'English title Spanish original title 2011', 'en-US');
    assert(niche.ok && niche.results.some(row => row.engine === 'google') && niche.results.some(row => row.engine === 'wikipedia'), 'niche-title pass combines original-title web and encyclopedia lookups');
  } finally {
    Google.prototype.Search = oldGoogleSearch;
    wikipediaApi.setLang = oldWikiSetLang;
    wikipediaApi.search = oldWikiSearch;
  }

  const invalidEngine = responseStub();
  await api({ method: 'GET', query: { engine: 'not-allowed', q: 'title' } }, invalidEngine);
  assert.strictEqual(invalidEngine.statusCode, 400, 'API endpoint rejects non-allowlisted engine IDs');
  const missingQuery = responseStub();
  await api({ method: 'GET', query: { engine: 'google', q: '' } }, missingQuery);
  assert.strictEqual(missingQuery.statusCode, 400, 'API endpoint rejects an empty query');
  const wrongMethod = responseStub();
  await api({ method: 'POST', query: {} }, wrongMethod);
  assert.strictEqual(wrongMethod.statusCode, 405, 'API endpoint only accepts the intended read-only method');

  const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
  const inlineScript = html.slice(html.indexOf('<script>') + 8, html.lastIndexOf('</script>'));
  const nicheStart = inlineScript.indexOf('function globalSearchQuery(identity,niche)');
  const nicheEnd = inlineScript.indexOf('\nasync function requestGlobalSearchEngine', nicheStart);
  assert(nicheStart >= 0 && nicheEnd > nicheStart, 'niche title query planner exists');
  const nicheContext = { identityUnique(values) { return [...new Set(values || [])]; } };
  vm.createContext(nicheContext);
  vm.runInContext(inlineScript.slice(nicheStart, nicheEnd) + '\nthis.buildGlobalQuery=globalSearchQuery;', nicheContext);
  const nicheQuery = nicheContext.buildGlobalQuery({
    title: 'My Heart Beats for Lola Volcán', titles: ['My Heart Beats for Lola Volcán', 'Mi corazón insiste en Lola Volcán'],
    type: 'tv', year: '2011', season: 1, episode: 1, imdbId: 'tt1929755', tmdbId: 35281,
  }, true);
  assert(nicheQuery.includes('Mi corazón insiste en Lola Volcán') && nicheQuery.includes('tt1929755') && nicheQuery.includes('TMDB 35281'), 'niche worker searches original title plus exact IMDb/TMDB identifiers');
  const searchStart = inlineScript.indexOf('async function findTitleServers(ctx,opts)');
  const searchEnd = inlineScript.indexOf('\nasync function playTitleServerOption', searchStart);
  assert(searchStart >= 0 && searchEnd > searchStart, 'title search function exists');
  const titleSearch = inlineScript.slice(searchStart, searchEnd);
  assert(titleSearch.includes('GLOBAL_SEARCH_ENGINE_POOL.map(async engine=>'), 'browser launches one JavaScript worker per global engine');
  assert(titleSearch.includes('Promise.allSettled(tasks)'), 'all global engine workers settle independently');
  assert(titleSearch.includes('Promise.race([runTitleCandidates,'), 'quick playback failover is not delayed by global web engines');
  assert(titleSearch.includes('collectAllStreams(richCtx'), '100-worker provider search remains in its own path');
  assert(!titleSearch.includes('runDiscoveryCycle(') && !titleSearch.includes('crawlStremioAddonsNet('), 'global and provider title searches never start the continuous crawler');
  assert(html.includes('Global web search leads') && html.includes('Web leads are not playable servers and are never auto-installed'), 'global pages are explicitly separated from playable/installed servers');

  console.log('PASS 10 GitHub adapters plus niche-title pass, zero-key serverless relay, and separate browser worker pools');
})().catch(error => { console.error(error); process.exit(1); });
