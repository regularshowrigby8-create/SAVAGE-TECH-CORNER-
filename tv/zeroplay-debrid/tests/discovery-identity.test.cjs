const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const path = require('path');

const root = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const script = html.slice(html.indexOf('<script>') + 8, html.lastIndexOf('</script>'));

function between(start, end) {
  const a = script.indexOf(start);
  assert(a >= 0, 'missing start ' + start);
  const b = script.indexOf(end, a + start.length);
  assert(b > a, 'missing end ' + end);
  return script.slice(a, b);
}

const context = {
  console,
  URL,
  Set,
  Map,
  Date,
  Math,
  Number,
  String,
  Array,
  Object,
  RegExp,
  Promise,
  watchlist: [],
  settings: {
    providers: [], providerTombstones: {}, addons: [], sites: [],
    discovery: { crawlers: {}, cursors: {}, seeds: [], stats: {}, githubQueries: [] },
    stream: { quality: 'auto', maxFileSizeGb: 5 },
  },
  saveSettings() {},
  dlog() {},
  prettyHostName(host) { return String(host || 'Source').split('.')[0]; },
  capBytes() { return 5_000_000_000; },
  healthScore() { return 50; },
  async fetchImdbId() { return ''; },
};
context.globalThis = context;
vm.createContext(context);

const code = [
  between('function titleOf(it)', '/* ---------------- built-in title identifier'),
  between('/* ---------------- built-in title identifier', '/* ---------------- demo catalog'),
  between('const DEFAULT_GITHUB_QUERIES=', 'function normalizeAddon'),
  between('function upsertProvider(rec, opts)', 'function identifyWebsiteProvider'),
  between('function getProvider(id)', 'function providerCounts'),
  between('function streamIdsForAddon(addon, identity, imdb)', 'function enabledAddons'),
  between('function parseStreamMeta(s)', 'async function collectAllStreams'),
].join('\n');
vm.runInContext(code, context, { filename: 'extracted-client.js' });

let passed = 0;
function ok(cond, label) { assert(cond, label); passed++; console.log('PASS', label); }
function eq(a, b, label) { assert.deepStrictEqual(a, b, label + `: ${JSON.stringify(a)} !== ${JSON.stringify(b)}`); passed++; console.log('PASS', label); }

(async () => {
  const identity = context.buildTitleIdentity({
    type: 'tv', id: 1399, title: 'Game of Thrones', year: '2011', season: 1, episode: 2,
    imdb: 'tt0944947', altTitles: ['GoT', 'Juego de tronos'],
  });
  eq(identity.imdbId, 'tt0944947', 'identity keeps IMDb id');
  eq(identity.tmdbId, 1399, 'identity keeps TMDB id');
  ok(identity.titles.includes('Juego de tronos'), 'identity keeps alternative titles');

  let verdict = context.identifyTitleCandidate({ title: 'Game of Thrones 2011 S01E02', type: 'series' }, identity);
  ok(verdict.accepted && verdict.score >= 95, 'exact series episode accepted');
  verdict = context.identifyTitleCandidate({ title: 'Game of Thrones S01E03' }, identity);
  ok(!verdict.accepted && verdict.hardReject, 'wrong episode hard-rejected');
  verdict = context.identifyTitleCandidate({ title: 'Game of Thrones', url: 'https://example.test/watch/tt9999999' }, identity);
  ok(!verdict.accepted && verdict.hardReject, 'wrong IMDb id hard-rejected');
  verdict = context.identifyTitleCandidate({ title: '1080p WEB-DL', url: 'https://cdn.example/file.mp4' }, identity, { idVerified: true });
  ok(verdict.accepted && verdict.score === 100, 'generic label accepted only through exact-ID route');
  verdict = context.identifyTitleCandidate({ title: 'Completely Different Movie 2024 1080p' }, identity, { idVerified: true });
  ok(!verdict.accepted && verdict.hardReject, 'conflicting stream label rejected despite ID route');

  const manifest = {
    id: 'example.addon', name: 'Example Server', version: '2.0',
    resources: [{ name: 'stream', idPrefixes: ['tmdb'] }, 'catalog', 'meta', 'subtitles', 'addon_catalog'],
    types: ['movie', 'series'],
    catalogs: [{ type: 'series', id: 'searchable', extra: [{ name: 'search' }] }],
    addonCatalogs: [{ type: 'all', id: 'community' }],
  };
  const info = context.manifestInfo(manifest);
  ok(info.valid && info.identification.kind === 'stremio-addon', 'manifest identified as addon server');
  ok(info.identification.supports.movie && info.identification.supports.series, 'server identifier records movie/series support');
  ok(info.identification.searchable && info.identification.identifierModes.includes('catalog-search'), 'server identifier records searchable catalogue');
  const provider = context.providerFromManifest('https://addon.example/manifest.json', manifest, { from: 'test' });
  eq(provider.status, 'installed', 'identified usable server auto-installs');
  ok(provider.categories.includes('streams') && provider.categories.includes('discovery'), 'provider installed by capabilities');
  const addon = context.providerToAddon(provider);
  ok(addon && addon.base === 'https://addon.example', 'installed provider syncs to playable addon registry');

  context.upsertProvider(provider);
  eq(context.settings.providers.length, 1, 'provider registry stores identified provider');
  const before = context.settings.providers.length;
  for (let i = 0; i < 1500; i++) {
    context.upsertProvider({
      id: 'github:example/repo-' + i, name: 'Repo ' + i, kind: 'github-project',
      categories: ['projects'], status: 'candidate', repoUrl: 'https://github.com/example/repo-' + i,
    });
  }
  eq(context.settings.providers.length, before + 1500, 'registry has no 30/60 style cap');
  context.removeProviderById(provider.id);
  ok(!context.getProvider(provider.id), 'provider can be removed while registry remains live');
  eq(context.upsertProvider(provider), null, 'removed provider is tombstoned against crawler resurrection');
  context.upsertProvider(provider, { force: true });
  ok(context.getProvider(provider.id), 'manual force re-add clears tombstone');

  context.fetchJsonLoose = async (url) => {
    if (url.includes('/catalog/series/searchable/search=')) {
      return { metas: [
        { id: 'custom-wrong', name: 'Game of Thrones S01E03', type: 'series' },
        { id: 'custom-got', name: 'Game of Thrones', type: 'series', releaseInfo: '2011' },
      ] };
    }
    if (url.includes('/meta/series/custom-got.json')) {
      return { meta: { id: 'custom-got', name: 'Game of Thrones', type: 'series', releaseInfo: '2011', videos: [{ id: 'custom-got-episode-2', season: 1, episode: 2 }] } };
    }
    if (url.includes('/stream/series/custom-got-episode-2.json')) {
      return { streams: [
        { name: 'Wrong Show S01E02 1080p', url: 'https://cdn.example/wrong.mp4' },
        { name: 'Game of Thrones S01E02 1080p', url: 'https://cdn.example/got.mp4' },
        { infoHash: 'abc' },
      ] };
    }
    return null;
  };
  const found = await context.searchAddonCatalog(addon, identity);
  ok(found && found.streamId === 'custom-got-episode-2', 'catalogue search identifies exact series and uses server episode id');
  const streams = await context.queryAddonStreams(addon, { identity });
  eq(streams.direct.length, 1, 'only identity-verified direct stream survives');
  eq(streams.direct[0].url, 'https://cdn.example/got.mp4', 'verified stream is the requested episode');
  ok(streams.identityRejected >= 1, 'wrong stream counted as identity rejection');
  eq(streams.torrents, 1, 'torrent remains hidden/counted');

  const server = require(path.join(root, 'api/extract.js'));
  const p = server._parse;
  eq(p.titleScore({ title: 'One Piece', url: 'https://x.test/anime/one-piece/ep-2' }, ['One Piece'], { type: 'tv', season: 1, episode: 1 }), 0, 'server rejects explicit wrong episode URL');
  ok(p.titleScore({ title: 'One Piece', url: 'https://x.test/anime/one-piece/ep-1' }, ['One Piece'], { type: 'tv', season: 1, episode: 1 }) >= 95, 'server accepts explicit correct episode URL');
  eq(p.titleScore({ title: 'Inception tt9999999' }, ['Inception'], { type: 'movie', imdb: 'tt1375666' }), 0, 'server rejects wrong IMDb identity');
  const sid = p.identifyTitleCandidate({ title: 'Inception', url: 'https://x.test/movie/tt1375666' }, ['Inception'], { type: 'movie', year: '2010', imdb: 'tt1375666' });
  ok(sid.accepted && sid.score === 100 && sid.reasons.includes('exact IMDb id'), 'server returns identity evidence');

  const staticChecks = [
    [!html.includes('function renderDebrid'), 'Real-Debrid UI removed'],
    [!html.includes('settings.tmdbKey'), 'personal TMDB key removed'],
    [!html.includes('settings.debrid'), 'Real-Debrid settings removed'],
    [html.includes('id="btnDiscoveryToggle"'), 'one continuous-search toggle exists'],
    [html.includes('runDiscoveryCycle'), 'continuous crawler engine exists'],
    [html.includes('identifyAndRegisterEndpoint'), 'server identification pipeline exists'],
    [!html.includes('enabledAddons().slice(0,12)'), '12-provider playback cap removed'],
    [!html.includes('sort((a,b)=>(b.stars||0)-(a.stars||0)).slice(0,60)'), '60-provider discovery cap removed'],
    [!fs.existsSync(path.join(root, 'api/debrid.js')), 'Vercel Real-Debrid API removed'],
    [!fs.existsSync(path.join(root, 'netlify/functions/debrid.js')), 'Netlify Real-Debrid API removed'],
    [!fs.readFileSync(path.join(root, 'netlify.toml'), 'utf8').includes('/api/debrid'), 'Netlify Real-Debrid route removed'],
  ];
  for (const [cond, label] of staticChecks) ok(cond, label);

  console.log(`\n${passed} checks passed`);
})().catch(err => { console.error(err); process.exit(1); });
