const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');
const root = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const script = html.slice(html.indexOf('<script>') + 8, html.lastIndexOf('</script>'));
function between(start, end) {
  const a = script.indexOf(start); assert(a >= 0, 'missing ' + start);
  const b = script.indexOf(end, a + start.length); assert(b > a, 'missing ' + end);
  return script.slice(a, b);
}
const installed = [];
const queried = [];
let saves = 0, syncs = 0;
const providers = [
  { id: 'candidate:match', status: 'candidate', enabled: true, name: 'Match addon', base: 'https://match.example', manifestUrl: 'https://match.example/manifest.json', categories: ['streams'], resources: ['stream'], types: ['movie'], idPrefixes: ['tt'], identification: { supports: { streams: true }, confidence: 90 } },
  { id: 'candidate:miss', status: 'candidate', enabled: true, name: 'No match addon', base: 'https://miss.example', manifestUrl: 'https://miss.example/manifest.json', categories: ['streams'], resources: ['stream'], types: ['movie'], idPrefixes: ['tt'], identification: { supports: { streams: true }, confidence: 80 } },
  { id: 'candidate:wrong', status: 'candidate', enabled: true, name: 'Unverified addon', base: 'https://wrong.example', manifestUrl: 'https://wrong.example/manifest.json', categories: ['streams'], resources: ['stream'], types: ['movie'], idPrefixes: ['tt'], identification: { supports: { streams: true }, confidence: 75 } },
  { id: 'candidate:disabled', status: 'candidate', enabled: false, name: 'Disabled addon', base: 'https://disabled.example', manifestUrl: 'https://disabled.example/manifest.json', categories: ['streams'], resources: ['stream'], types: ['movie'], idPrefixes: ['tt'], identification: { supports: { streams: true } } },
  { id: 'installed:known', status: 'installed', enabled: true, name: 'Already installed', base: 'https://known.example', manifestUrl: 'https://known.example/manifest.json', categories: ['streams'], resources: ['stream'], identification: { supports: { streams: true } } },
  { id: 'candidate:local', status: 'candidate', enabled: true, name: 'Local endpoint', base: 'https://127.0.0.1', manifestUrl: 'https://127.0.0.1/manifest.json', categories: ['streams'], resources: ['stream'], identification: { supports: { streams: true } } },
  { id: 'candidate:nostream', status: 'candidate', enabled: true, name: 'Non-stream addon', base: 'https://catalog.example', manifestUrl: 'https://catalog.example/manifest.json', identification: { supports: { streams: false } } },
];
const ctx = {
  Date, Math, Number, String, Array, Object, Set, Map, URL, Promise,
  settings: { providers, providerTombstones: {}, addons: [], discovery: { cursors: {} } },
  discoveryCfg() { return ctx.settings.discovery; },
  queryAddonStreams: async addon => {
    queried.push(addon.base);
    if (addon.base === 'https://match.example') return { direct: [{ url: 'https://cdn.example/exact-title.mp4', addonName: addon.name, addonBase: addon.base }], identityVerified: 1 };
    if (addon.base === 'https://wrong.example') return { direct: [{ url: 'https://cdn.example/unverified.mp4', addonName: addon.name, addonBase: addon.base }], identityVerified: 0 };
    return { direct: [], identityVerified: 0 };
  },
  upsertProvider: rec => { installed.push(rec); return { provider: rec }; },
  syncInstalledAddonsFromProviders() { syncs++; },
  saveSettings() { saves++; },
  uniqueStringList(values) { return [...new Set((values || []).map(String))]; },
  cleanHttpUrl(raw) { try { const u = new URL(raw); return /^https?:$/.test(u.protocol) ? u.href : ''; } catch { return ''; } },
  manifestBaseFromUrl(url) { return String(url).replace(/\/manifest\.json(?:\?.*)?$/i, '').replace(/\/$/, ''); },
  manifestInfo() { return { valid: true, categories: ['streams'], resources: ['stream'], types: ['movie'], idPrefixes: ['tt'], catalogDefs: [], addonCatalogs: [], searchCatalogs: [], playableStream: true, identification: { installable: true, playableStream: true, supports: { streams: true } } }; },
};
ctx.globalThis = ctx;
vm.createContext(ctx);
vm.runInContext(between('function providerFromManifest(', 'function providerToAddon(') + between('function isPublicHttpsProviderBase(', 'async function measureSpeed('), ctx);

(async () => {
  const deferred = ctx.providerFromManifest('https://deferred.example/manifest.json', { id: 'test', name: 'Deferred' }, { deferInstall: true });
  assert.strictEqual(deferred.status, 'candidate', 'public catalogue stream addons stay candidates until a title match');
  assert.strictEqual(deferred.titleScopedDiscovery, true, 'title-scoped marker is retained after manifest identification');
  const normal = ctx.providerFromManifest('https://manual.example/manifest.json', { id: 'manual', name: 'Manual' }, {});
  assert.strictEqual(normal.status, 'installed', 'manual or explicitly seeded providers keep existing install behavior');

  const candidates = ctx.titleSearchCandidates(100);
  assert.deepStrictEqual(candidates.map(p => p.id), ['candidate:match', 'candidate:miss', 'candidate:wrong']);
  const result = await ctx.collectTitleCandidateStreams({ identity: { type: 'movie', tmdbId: 1, imdbId: 'tt0000001', title: 'Exact Title' } }, { limit: 100, concurrency: 12 });
  assert.strictEqual(result.queried, 3, 'only eligible, enabled, public HTTPS stream candidates are queried');
  assert.strictEqual(result.matchedProviders, 1, 'only a provider returning an identity-verified direct title stream matches');
  assert.deepStrictEqual(JSON.parse(JSON.stringify(result.direct.map(s => s.url))), ['https://cdn.example/exact-title.mp4']);
  assert.deepStrictEqual(installed.map(p => p.id), ['candidate:match'], 'unmatched and unverified providers are never installed');
  assert.strictEqual(installed[0].titleScopedDiscovery, false, 'a verified title match graduates its provider from candidate status');
  assert.strictEqual(syncs, 1, 'matched addons are synchronized once after the candidate batch');
  assert.strictEqual(saves, 1, 'matched providers persist once after the candidate batch');
  assert.strictEqual(queried.includes('https://127.0.0.1'), false, 'local/private destinations are not queried');
  for (let i = 0; i < 108; i++) providers.push({ id: 'candidate:extra-' + i, status: 'candidate', enabled: true, base: 'https://extra-' + i + '.example', manifestUrl: 'https://extra-' + i + '.example/manifest.json', resources: ['stream'], identification: { supports: { streams: true }, confidence: 10 } });
  const firstBatch = ctx.titleSearchCandidates(100, 0).map(p => p.id);
  const nextBatch = ctx.titleSearchCandidates(100, 100).map(p => p.id);
  assert.strictEqual(firstBatch.length, 100, 'each title-search pass is capped at 100 candidates');
  assert(new Set(firstBatch.concat(nextBatch)).size > 100, 'candidate cursor rotates so providers beyond the first 100 are reached');
  console.log('PASS title-scoped provider discovery and exact-match-only installation');
})().catch(error => { console.error(error); process.exit(1); });
