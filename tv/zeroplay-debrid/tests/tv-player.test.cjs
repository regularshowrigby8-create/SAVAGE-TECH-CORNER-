const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const path = require('path');
const root = path.resolve(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const script = html.slice(html.indexOf('<script>') + 8, html.lastIndexOf('</script>'));
function between(start, end) {
  const a = script.indexOf(start); assert(a >= 0, 'missing ' + start);
  const b = script.indexOf(end, a + start.length); assert(b > a, 'missing ' + end);
  return script.slice(a, b);
}
const listeners = {};
const ctx = {
  console, URL, URLSearchParams, Set, Map, Date, Math, Number, String, Array, Object, RegExp,
  Promise, Error, setTimeout, clearTimeout,
  settings: { stream: { quality: '720p', volume: .8, rate: 1 }, health: {}, addons: [] },
  navigator: {},
  playerState: null,
  window: { addEventListener(type, fn) { (listeners[type] ||= []).push(fn); } },
  esc: s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])),
  enabledAddons() { return ctx.settings.addons.filter(a => a.enabled !== false); },
  healthScore() { return 50; },
  refreshPlayerQualityOptions() {},
  toast() {},
};
ctx.globalThis = ctx;
vm.createContext(ctx);
vm.runInContext([
  between('const GENRES_MOVIE', 'function demoResponse'),
  between('function formatTime(sec)', 'function playerLoading'),
  between('const FASTSTREAM_ORIGIN', 'function registerProgress'),
  between('function groupStreamsByServer', 'const titleServerCache'),
].join('\n'), ctx);

let n = 0;
const ok = (c, label) => { assert(c, label); n++; console.log('PASS', label); };
const eq = (a, b, label) => { assert.deepStrictEqual(JSON.parse(JSON.stringify(a)), b, label); n++; console.log('PASS', label); };

eq(ctx.cmGenreIds('movie', ['Action', 'Science Fiction']), [28, 878], 'Cinemeta movie genres map to category IDs');
eq(ctx.cmGenreIds('tv', ['Sci-Fi', 'Drama']), [10765, 18], 'Cinemeta series genres map to category IDs');
eq(ctx.trailerKeyFromData({ trailerStreams: [{ ytId: 'abcDEF12345' }] }), 'abcDEF12345', 'Cinemeta trailerStreams key extracted');
eq(ctx.trailerKeyFromData({ trailers: [{ source: 'xyzDEF12345' }] }), 'xyzDEF12345', 'Cinemeta trailers key extracted');
eq(ctx.trailerKeyFromData({ videos: { results: [
  { site: 'YouTube', type: 'Teaser', key: 'teaser12345' },
  { site: 'YouTube', type: 'Trailer', key: 'trailer1234' },
] } }), 'trailer1234', 'TMDB official trailer preferred over teaser');
eq(ctx.normalizeQualityLabel('4K UHD'), '2160p', '4K quality normalized');
eq(ctx.normalizeQualityLabel('HD'), '720p', 'HD quality normalized');
eq(ctx.formatTime(3661), '1:01:01', 'player time formatting');

const variants = [
  { url: 'https://cdn.test/movie-2160.mp4', quality: '2160p' },
  { url: 'https://cdn.test/movie-720.mp4', quality: '720p' },
  { url: 'https://cdn.test/movie-1080.mp4', quality: '1080p' },
];
eq(ctx.chooseVariantForQuality(variants, '720p').url, 'https://cdn.test/movie-720.mp4', 'explicit quality selects the matching real variant');
eq(ctx.chooseVariantForQuality(variants, 'auto').url, 'https://cdn.test/movie-2160.mp4', 'auto quality selects the best returned variant');
const levelOptions = ctx.qualityOptionsHTML({ engine: 'faststream-api', qualitySelection: 'auto', levels: [
  { id: 'v720', height: 720, bitrate: 2500000 },
  { id: 'v1080', height: 1080, bitrate: 5000000 },
] });
ok(levelOptions.indexOf('1080p') < levelOptions.indexOf('720p') && levelOptions.includes('value="level:v1080"'), 'FastStream levels become real quality options');

const groups = ctx.groupStreamsByServer([
  { url: 'https://a.test/2160.mp4', addonName: 'Server A', addonBase: 'https://a.test/addon', quality: '2160p', identityScore: 99 },
  { url: 'https://a.test/720.mp4', addonName: 'Server A', addonBase: 'https://a.test/addon', quality: '720p', identityScore: 99 },
  { url: 'https://b.test/1080.mp4', addonName: 'Server B', addonBase: 'https://b.test/addon', quality: '1080p', identityScore: 97 },
]);
eq(groups.length, 2, 'direct streams grouped by server instead of dumped as unrelated rows');
eq(groups[0].variants[0].quality, '720p', 'server variants sorted around the preferred quality');
const movieIdentity = { type: 'movie', tmdbId: 27205, imdbId: 'tt1375666', season: 1, episode: 1 };
const tvIdentity = { type: 'tv', tmdbId: 1399, imdbId: 'tt0944947', season: 2, episode: 3 };
ok(ctx.sourceSupportsTitle({ id: 'vidstuck', kind: 'builtin' }, tvIdentity).supported, 'built-in exact-ID episode route is title-addressable');
ok(!ctx.sourceSupportsTitle({ id: 'x', kind: 'custom', tvTpl: 'https://x.test/movie/{tmdb}' }, tvIdentity).supported, 'movie-only template excluded for an episode');
ok(!ctx.sourceSupportsTitle({ id: 'a', kind: 'addon', identification: { supports: { movie: false } } }, movieIdentity).supported, 'addon explicitly lacking movie support excluded');
const sorted = ctx.sortTitleServerOptions([
  { id: 'route:embedwave', kind: 'route', sourceId: 'embedwave', name: 'Embedwave', score: 999 },
  { id: 'route:vidstuck', kind: 'route', sourceId: 'vidstuck', name: 'VidStuck', score: 50 },
  { id: 'direct:a', kind: 'direct', sourceId: 'a', name: 'A', score: 80 },
]);
eq(sorted.map(x => x.id), ['direct:a', 'route:vidstuck', 'route:embedwave'], 'verified direct first and Embedwave always last');

(async () => {
  const sent = [];
  const frame = { contentWindow: { postMessage(msg, origin) { sent.push({ msg, origin }); } } };
  ctx.resetFastStreamBridge(frame);
  const readyPromise = ctx.waitForFastStreamReady(1000);
  const handler = listeners.message.at(-1);
  handler({ origin: 'https://faststream.online', source: frame.contentWindow, data: { type: 'faststream:event', event: 'ready', state: { paused: true } } });
  eq(await readyPromise, true, 'FastStream EmbedAPI ready handshake resolves');
  const commandPromise = ctx.fsCommand('getLevels', {}, 1000);
  eq(sent[0].origin, 'https://faststream.online', 'FastStream command uses the official origin');
  eq(sent[0].msg.type, 'faststream:command', 'FastStream command envelope is correct');
  handler({ origin: 'https://faststream.online', source: frame.contentWindow, data: { type: 'faststream:response', id: sent[0].msg.id, ok: true, result: { video: [{ id: 'v1' }] } } });
  eq((await commandPromise).video[0].id, 'v1', 'FastStream command response resolves by correlation ID');
  const state = ctx.normalizeFastStreamState({ paused: false, buffering: true, currentTime: 40, duration: 100, buffered: [{ start: 0, end: 65 }], volume: .7, playbackRate: 1.25 });
  eq(state.buffered, 25, 'FastStream buffered-ahead state normalized');
  eq(state.rate, 1.25, 'FastStream playback rate normalized');

  const statics = [
    [html.includes('id="menuDrawer"') && html.includes('data-nav="categories"'), 'TV menu drawer and Categories navigation present'],
    [html.includes('function renderCategories()') && html.includes('route==="categories"'), 'Categories route is wired'],
    [html.includes('class="trailer-layer"') && html.includes('function startCardPreview'), 'trailer preview lives inside poster title blocks'],
    [html.includes('id="playerQuality"') && html.includes('setVideoLevel'), 'unified player exposes real FastStream quality selection'],
    [html.includes('frame.src="https://faststream.online/player/"') && html.includes('waitForFastStreamReady(4500)'), 'FastStream API handshake precedes fallback'],
    [html.includes('https://faststream.online/player/#'), 'legacy FastStream HLS/DASH fallback retained'],
    [html.includes('r.status!==206') && html.includes('Math.min(4,Number(cfg.primeThreads)||2)') && html.includes('primeBudgetMb'), 'direct priming capped to genuine range responses and configured worker/budget limits'],
    [html.includes('async function tryNextDirectVariant(reason){\n  const st=playerState; if(!st) return false;\n  if(streamCfg().autoFailover===false) return false;') && html.includes('Confirmed direct playback stalled on ') && html.includes('hasAdvanced&&lastObservedAdvanceAt'), 'automatic switching master switch and confirmed native stall detection are wired'],
    [html.includes('servers appear only after a match') && html.includes('groupStreamsByServer'), 'server list is title-specific and grouped'],
    [!/id=\\"playerSource\\"(?!Line)/.test(html) && !html.includes('id="detailSource"'), 'all-server player/detail dropdowns removed'],
    [html.includes('function moveTvFocus') && html.includes('handlePlayerKeyboard'), 'TV spatial and player keyboard navigation present'],
    [html.includes('tabindex="-1" src="https://faststream.online/player/#') || html.includes('f.setAttribute("tabindex","-1")'), 'cross-origin frames do not steal initial TV focus'],
  ];
  for (const [cond, label] of statics) ok(cond, label);
  console.log(`\n${n} TV/player checks passed`);
})().catch(err => { console.error(err); process.exit(1); });
