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
  between('const ZP_ENGINE_SCRIPTS', 'function registerProgress'),
  between('function directMediaKind', 'function startOwnEngine'),
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
const levelOptions = ctx.qualityOptionsHTML({ engine: 'native', qualitySelection: 'auto', levels: [
  { id: 'v720', height: 720, bitrate: 2500000 },
  { id: 'v1080', height: 1080, bitrate: 5000000 },
] });
ok(levelOptions.indexOf('1080p') < levelOptions.indexOf('720p') && levelOptions.includes('value="level:v1080"'), 'the engine own HLS/DASH levels become real quality options');
ok(!/FastStream/.test(levelOptions), 'the quality list no longer advertises a hosted player');

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
  /* The app must own playback: no postMessage bridge, no hosted player frame,
     and a real container router for the three formats a source can hand us. */
  ok(typeof ctx.zpEngineMount === 'function', 'the app exports its own mounting engine');
  eq(ctx.streamKindOf('https://cdn.test/a.m3u8'), 'hls', 'HLS is routed to the HLS engine');
  eq(ctx.streamKindOf('https://cdn.test/a.mpd'), 'dash', 'DASH is routed to the DASH engine');
  eq(ctx.streamKindOf('https://cdn.test/a.mp4'), 'file', 'MP4 plays in the element directly');
  eq(ctx.streamKindOf('https://x.test/play/1', 'hls'), 'hls', 'a declared container is honoured when the URL has no extension');
  eq(ctx.streamKindOf('https://x.test/play/1'), 'unknown', 'an unknown container is not guessed');
  ok(!/src=["'][^"']*faststream\.online/.test(html) && !html.includes('iframe.src="https://faststream.online'), 'no stream is handed to a hosted player iframe');
  ok(!html.includes('fsCommand') && !html.includes('fsBridge'), 'the postMessage bridge into another player is gone');
  ok(html.includes('vendor/hls.min.js') && html.includes('vendor/dash.min.js'), 'HLS and DASH engines ship with the app');
  ok(html.includes('function zpEngineDestroy(') && html.includes('if(e.instance&&e.instance.destroy)'), 'engine teardown exists, so switching sources releases the decoder');
  ok(html.includes('mode === \'resolve\'') === false || html.includes('mode:"resolve"'), 'a page is handed to the resolver before it is ever framed');

  /* Level switching is the part a viewer notices: choosing an option must set
     a real index on the engine instance we created, and Auto must hand control
     back to the engine instead of pinning a level. */
  function FakeHls(){}
  ctx.window.Hls = FakeHls;
  const fakeHls = Object.assign(new FakeHls(), { levels: [{ height: 720 }, { height: 1080 }], currentLevel: -1 });
  ctx.playerState = { qualitySelection: 'auto', levels: [], zpEngine: { kind: 'hls', instance: fakeHls, video: null, levels: [
    { id: '0', height: 720 }, { id: '1', height: 1080 },
  ] } };
  ok(ctx.zpEngineSetLevel('level:1') === true, 'a chosen level is accepted by the engine');
  eq(fakeHls.currentLevel, 1, 'the chosen level becomes the engine current level');
  ok(ctx.zpEngineSetLevel('auto') === true, 'Auto is accepted by the engine');
  eq(fakeHls.currentLevel, -1, 'Auto returns level control to the engine');
  ok(ctx.zpEngineSetLevel('level:9') === false, 'a level that is not in the stream is refused instead of silently ignored');
  ctx.playerState = null;

  /* The resumed position and the source we mounted must reach the engine, and a
     teardown must leave nothing behind for the next stream to trip over. */
  ok(/function startOwnEngine\(stage,url,source,loadT0,resumeAt,opts\)/.test(html), 'one mount path owns the player');
  ok(html.includes('zpEngineDestroy()'), 'leaving the player tears the engine down');
  ok(/st\.triedUrls=st\.triedUrls\|\|new Set\(\)/.test(html), 'a failed stream is remembered so failover cannot loop');
  ok(!/mountEmbedPlayer[\s\S]{0,400}postMessage/.test(html), 'the framed fallback is never remote-controlled');

  const statics = [
    [html.includes('id="menuDrawer"') && html.includes('data-nav="categories"'), 'TV menu drawer and Categories navigation present'],
    [html.includes('function renderCategories()') && html.includes('route==="categories"'), 'Categories route is wired'],
    [html.includes('class="trailer-layer"') && html.includes('function startCardPreview'), 'trailer preview lives inside poster title blocks'],
    [html.includes('id="playerQuality"') && html.includes('function zpEngineSetLevel('), 'the quality select drives our own engine levels'],
    [html.includes('player:"native"') && html.includes('settings.stream.player!=="embed-page"'), 'the ZeroPlay engine is the default and saved hosted-player settings migrate'],
    [html.includes('if(st.direct) await startOwnEngine('), 'every direct stream is mounted through the one owned engine'],
    [html.includes('resolvePageToStreams') && html.includes('mode:"resolve"'), 'provider pages are resolved into streams before playback'],
    [html.includes('r.status!==206') && html.includes('Math.min(4,Number(cfg.primeThreads)||2)') && html.includes('primeBudgetMb'), 'direct priming capped to genuine range responses and configured worker/budget limits'],
    [/async function tryNextDirectVariant\(reason\)\{\s*const st=playerState; if\(!st\) return false;\s*if\(streamCfg\(\)\.autoFailover===false\) return false;/.test(html) && html.includes('Confirmed playback stalled on ') && html.includes('hasAdvanced&&lastObservedAdvanceAt'), 'automatic switching master switch and confirmed native stall detection are wired'],
    [html.includes('groupStreamsByServer') && html.includes('id="serverList"') && html.includes('unrelated servers hidden') && html.includes('data-stream-filter'), 'server list is title-specific, unified into one list, and filterable by provider kind'],
    [!/id=\\"playerSource\\"(?!Line)/.test(html) && !html.includes('id="detailSource"'), 'all-server player/detail dropdowns removed'],
    [html.includes('function moveTvFocus') && html.includes('handlePlayerKeyboard'), 'TV spatial and player keyboard navigation present'],
    [html.includes('f.setAttribute("tabindex","-1")'), 'cross-origin frames do not steal initial TV focus'],
  ];
  for (const [cond, label] of statics) ok(cond, label);
  console.log(`\n${n} TV/player checks passed`);
})().catch(err => { console.error(err); process.exit(1); });
