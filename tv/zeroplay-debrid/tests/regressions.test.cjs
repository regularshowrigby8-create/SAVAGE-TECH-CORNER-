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
const ctx = {
  console, URL, URLSearchParams, Set, Map, Date, Math, Number, String, Array, Object, RegExp,
  settings: { stream: { maxFileSizeGb: 5, quality: 'auto', dataSaver: 'off' }, health: {}, disabledSources: [] },
  navigator: {},
  saveSettings() {},
};
ctx.globalThis = ctx; vm.createContext(ctx);
vm.runInContext([
  between('const SOURCES=', '/* ---------------- FastStream preload'),
  between('const CAP_GB_HARD', 'async function probeFileSize'),
  between('function applyTemplate', 'const imdbCache'),
  between('function prettyHostName', 'function validateTemplate'),
  'function validateTemplate(t){ if(!t) return true; try{ const u=new URL(t.replace(/\\{[^}]+\\}/g,"1")); return u.protocol==="https:"||u.protocol==="http:"; }catch{ return false; } }',
  between('function cleanHttpUrl(raw)', 'function providerStableKey'),
  between('function classifySeedUrl(raw)', 'function providerCandidateFromSeed'),
  between('function parseFeedData(data)', 'function sourceSignature'),
  between('function normalizePlayerEvent(data)', 'window.addEventListener("message"'),
].join('\n'), ctx);

let n = 0;
const ok = (c, label) => { assert(c, label); n++; console.log('PASS', label); };
const eq = (a, b, label) => { assert.deepStrictEqual(a, b, label); n++; console.log('PASS', label); };

ok(ctx.sourceURL({type:'movie',id:27205},{},'vidstuck').startsWith('https://vidstuck.cc/embed/movie/27205'), 'VidStuck movie URL retained');
ok(ctx.sourceURL({type:'tv',id:1399},{season:2,episode:3},'zxcstream').startsWith('https://zxcstream.cc/player/tv/1399/2/3'), 'ZXCStream episode URL retained');
ok(ctx.sourceURL({type:'movie',id:27205},{},'embedwave').startsWith('https://embedwave.cc/embed/movie/27205'), 'Embedwave URL retained');
eq(ctx.capGb(), 5, '5 GB hard cap retained');
ok(!ctx.decideSizeCap(5_000_000_001, ctx.capBytes()).allowed, 'file above cap rejected');
ok(ctx.decideSizeCap(4_999_999_999, ctx.capBytes()).allowed, 'file below cap allowed');
eq(ctx.applyTemplate('https://x.test/{type}/{tmdb}/{season}/{episode}?imdb={imdb}', {type:'tv',id:1399,season:2,episode:3,imdb:'tt0944947'}), 'https://x.test/tv/1399/2/3?imdb=tt0944947', 'URL templates still resolve canonical IDs');
let parsed = ctx.parsePastedLink('https://example.test/watch/tt1375666/1/2');
ok(parsed && parsed.tvTpl.includes('{imdb}/{season}/{episode}'), 'pasted IMDb episode link becomes template');
parsed = ctx.parsePastedLink('https://example.test/home');
ok(parsed && parsed.site && !parsed.movieTpl, 'ordinary homepage remains lookup-only website');
eq(ctx.parseFeedData([{ name:'Template', movie:'https://x.test/movie/{tmdb}' }]).length, 1, 'legacy feed template still accepted');
eq(ctx.parseFeedData([{ url:'https://github.com/example/project' }]).length, 0, 'GitHub feed entry is not misclassified as template');
eq(ctx.parseFeedData([{ url:'https://addon.example/manifest.json' }]).length, 0, 'manifest feed entry is not misclassified as template');
ok(ctx.normalizePlayerEvent({type:'PLAYER_EVENT',data:{player_progress:10,player_duration:100,player_info:{tmdb:27205,mediaType:'movie'}}}).id === 27205, 'PLAYER_EVENT normalization retained');

const srv = require(path.join(root, 'api/extract.js'))._parse;
const results = srv.parseResultLinks('<a href="/anime/one-piece/ep-1" title="One Piece">One Piece</a><a href="/anime/naruto">Naruto</a>', 'https://site.test/search?keyword=One+Piece');
ok(results.some(r => r.url.endsWith('/ep-1') && r.title === 'One Piece'), 'dynamic result parser retains title links');
ok(srv.searchTemplatesFromHtml('<form action="/search"><input name="keyword"></form>', 'https://site.test/').length >= 1, 'website search form discovery retained');
eq(srv.buildSearchUrl('https://site.test/search/{slug}', 'One Piece'), 'https://site.test/search/one-piece', 'slug search URL retained');
ok(srv.adjustEpisodeInUrl('https://site.test/anime/one-piece/ep-1180',1,2).endsWith('/ep-2'), 'episode URL adjustment retained');
ok(srv.findEpisodeUrl('<a href="/anime/one-piece/ep-2">2</a>', 'https://site.test/anime/one-piece/ep-1', 1, 2).endsWith('/ep-2'), 'child episode discovery retained');
const watch = srv.parseWatch('<title>One Piece Episode 1</title><iframe src="https://player.test/embed/1"></iframe><source src="https://cdn.test/video.mp4">', 'https://site.test/watch');
ok(watch.embed.includes('player.test') && watch.direct.includes('https://cdn.test/video.mp4'), 'watch parser retains iframe and direct media');

const statics = [
  [html.includes('"/api/extract?mode=json&url="+encodeURIComponent(url)'), 'Cinemeta/deployment JSON relay path retained'],
  [html.includes('const LAST_RESORT=new Set(["embedwave"])'), 'Embedwave last-resort registry retained'],
  [html.includes('https://faststream.online/player/#'), 'embedded FastStream direct player retained'],
  [html.includes('settings.source==="embedwave") settings.source="vidstuck"'), 'Embedwave cannot remain saved default'],
  [html.includes('delete settings.siteCatalogues'), 'legacy imported website catalogues still purged'],
  [html.includes('no titles or covers imported'), 'lookup-only website rule visible'],
  [html.includes('identifyAndRegisterEndpoint'), 'crawler server identification present'],
  [html.includes('searchAddonCatalog'), 'searchable addon catalogues present'],
];
for (const [cond,label] of statics) ok(cond,label);
console.log(`\n${n} regression checks passed`);
