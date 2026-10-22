/* Search (Cinemeta keyless resolver) + FastStream integration + self-hosted
   adaptive engines + APK channel. The search test is FUNCTIONAL: it replays
   the exact payload shapes verified live against v3-cinemeta.strem.io, where
   search previews omit moviedb_id but full metas include it. */
const fs = require('fs'), vm = require('vm'), assert = require('assert'), path = require('path');
const root = path.resolve(__dirname, '..');
const repo = path.resolve(root, '..', '..');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const script = html.slice(html.indexOf('<script>') + 8, html.lastIndexOf('</script>'));

function between(a, b) {
  const i = script.indexOf(a); assert(i >= 0, a);
  const j = script.indexOf(b, i + a.length); assert(j > i, b);
  return script.slice(i, j);
}
function fnSource(name) {
  const i = script.indexOf('function ' + name);
  assert(i >= 0, 'function ' + name);
  let j = script.indexOf('{', i), depth = 0, k = j;
  for (; k < script.length; k++) {
    if (script[k] === '{') depth++;
    else if (script[k] === '}') { depth--; if (depth === 0) { k++; break; } }
  }
  return script.slice(i, k);
}

/* ---- live-verified Cinemeta payload shapes ---- */
const searchPreviews = { metas: [
  { id: 'tt0499549', type: 'movie', name: 'Avatar', imdb_id: 'tt0499549', poster: 'https://images.metahub.space/poster/small/tt0499549/img', releaseInfo: '2009' },
  { id: 'tt0000000', type: 'movie', name: 'Unresolvable Preview', imdb_id: 'tt0000000' },
  { id: 'tt1630029', type: 'movie', name: 'Avatar The Way of Water', imdb_id: 'tt1630029', poster: 'https://images.metahub.space/poster/small/tt1630029/img', releaseInfo: '2022' },
]};
const fullMetas = {
  'tt0499549': { meta: { id: 'tt0499549', type: 'movie', name: 'Avatar', imdb_id: 'tt0499549', moviedb_id: 19995, released: '2009-12-15', imdbRating: '7.9', genres: ['Action', 'Adventure'], poster: 'https://images.metahub.space/poster/medium/tt0499549/img', description: 'A paraplegic Marine dispatched to the moon Pandora.' } },
  'tt1630029': { meta: { id: 'tt1630029', type: 'movie', name: 'Avatar The Way of Water', imdb_id: 'tt1630029', moviedb_id: 76600, released: '2022-12-14', imdbRating: '7.6', genres: ['Adventure'], poster: 'https://images.metahub.space/poster/medium/tt1630029/img' } },
};
const fetchCalls = [];
async function stubFetch(url) {
  const u = String(url); fetchCalls.push(u);
  if (u.includes('/catalog/movie/top/search=')) {
    return { ok: true, status: 200, async json() { return searchPreviews; } };
  }
  const m = u.match(/\/meta\/movie\/(tt\d+)\.json/);
  if (m && fullMetas[m[1]]) {
    return { ok: true, status: 200, async json() { return fullMetas[m[1]]; } };
  }
  return { ok: false, status: 404, async json() { return {}; } };
}

const context = {
  console, URL, Set, Map, Date, Math, Number, String, Array, Object, RegExp, Promise, JSON,
  AbortController, setTimeout, clearTimeout, encodeURIComponent, parseFloat, isNaN,
  fetch: stubFetch, imdbCache: {},
};
context.globalThis = context;
vm.createContext(context);
vm.runInContext(
  between('const GENRES_MOVIE', 'function cmGenreIds') + '\n' +
  fnSource('cmGenreIds') + '\n' + fnSource('trailerKeyFromData') + '\n' +
  between('const CINEMETA =', 'function cmWrap') + '\n' +
  fnSource('directMediaKind') + '\n' + fnSource('fastStreamModeFor'),
  context, { filename: 'search-extract.js' }
);

let n = 0;
const ok = (c, label) => { assert(c, label); n++; console.log('PASS', label); };

(async () => {
  /* Root-cause documentation: the old path really did drop every preview. */
  ok(context.cmMapMeta(searchPreviews.metas[0]) === null,
    'raw Cinemeta search preview (no moviedb_id) is dropped by cmMapMeta - the old bug');

  const results = await context.cmSearchCatalog('movie', 'avatar');
  ok(Array.isArray(results) && results.length === 2,
    'resolver returns the two resolvable results and skips the dead preview');
  ok(results[0].id === 19995 && results[0].title === 'Avatar',
    'Avatar resolves to TMDB 19995 in first (relevance) position');
  ok(results[1].id === 76600 && /way of water/i.test(results[1].title),
    'Avatar The Way of Water resolves to TMDB 76600 second');
  ok(fetchCalls.some(u => u.includes('/meta/movie/tt0499549.json')),
    'resolver hydrates previews through /meta/movie/{imdb}.json');
  ok(results[0].imdb_id === 'tt0499549' && results[0].poster_url.includes('/poster/medium/'),
    'resolved items carry imdb id and upgraded poster for downstream stream lookup');

  /* FastStream upstream EmbedAPI modes */
  ok(context.fastStreamModeFor('https://cdn.example/video.m3u8') === 'accelerated_hls', 'HLS load mode = accelerated_hls');
  ok(context.fastStreamModeFor('https://cdn.example/video.mpd') === 'accelerated_dash', 'DASH load mode = accelerated_dash');
  ok(context.fastStreamModeFor('https://cdn.example/video.mp4?sig=1') === 'accelerated_mp4', 'MP4 load mode = accelerated_mp4');
  ok(context.fastStreamModeFor('https://cdn.example/video.webm') === undefined, 'unknown containers get no forced mode');

  /* Integration wiring (static, precise) */
  const wiring = [
    ['mode:fastStreamModeFor(url)', 'FastStream load passes explicit upstream mode'],
    ['waitForFastStreamReady(ANDROID_TV_APP?12000:6000)', 'TV waits up to 12s for the FastStream ready event'],
    ['fsCommand("ping"', 'EmbedAPI ping confirms the session after subscribe'],
    ['fsCommand("getSubtitles"', 'FastStream subtitles are read through EmbedAPI'],
    ['fsCommand("setSubtitleTrack"', 'FastStream subtitle selection goes through EmbedAPI'],
    ['min(workers, previews.length', 'search hydration is concurrency-capped'],
    ['directOnly: true', 'search meta hydration stays on the fast direct Cinemeta path'],
    ['const limit = tv ? 16 : 24', 'TV search hydrates fewer previews per type'],
    ['const mixed=[]', 'multi search interleaves movie and TV relevance'],
    ['box.querySelector(".poster-card")', 'TV search focuses the first result card'],
    ['loadVendorScript(useDash?"dash":"hls")', 'adaptive engine scripts are lazy-loaded self-hosted'],
    ['window.dashjs.MediaPlayer().create()', 'dash.js drives MPEG-DASH in our player'],
    ['new window.Hls(', 'hls.js drives HLS when native HLS is unavailable'],
    ['vendorCanPlayDirect(url)', 'TV fallback chain includes the self-hosted engines'],
    ['destroyVendorPlayers(st)', 'vendor players are destroyed on engine switch/close'],
  ];
  for (const [needle, label] of wiring) ok(html.includes(needle), label);

  /* Shipped vendor payloads */
  const hlsSize = fs.statSync(path.join(root, 'vendor', 'hls.min.js')).size;
  const dashSize = fs.statSync(path.join(root, 'vendor', 'dash.all.min.js')).size;
  ok(hlsSize > 300000, 'vendor/hls.min.js ships the real hls.js build (' + hlsSize + ' bytes)');
  ok(dashSize > 300000, 'vendor/dash.all.min.js ships the real dash.js build (' + dashSize + ' bytes)');
  ok(fs.existsSync(path.join(root, 'vendor', 'VENDOR.md')), 'vendor provenance is documented');

  /* Build + hosting carry the engines */
  const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
  ok(pkg.scripts.build.includes('vendor'), 'Vercel build copies vendor engines into dist');
  ok(pkg.scripts.test.includes('search-faststream.test.cjs'), 'this suite runs in npm test');

  /* APK channel */
  const vercel = JSON.parse(fs.readFileSync(path.join(root, 'vercel.json'), 'utf8'));
  const redir = (vercel.redirects || []).find(r => r.source === '/tv.apk');
  ok(redir && /zeroplay-tv-v1\.0\.3\/zeroplay-tv\.apk$/.test(redir.destination),
    'zeroplay-rd.vercel.app/tv.apk permanently redirects to the signed TV APK asset');
  const gradle = fs.readFileSync(path.join(repo, 'tv', 'android-tv', 'app', 'build.gradle.kts'), 'utf8');
  ok(gradle.includes('versionName = "1.0.3"') && gradle.includes('versionCode = 4'),
    'Android TV app bumped to 1.0.3 (versionCode 4) for the native input changes');
  const workflow = fs.readFileSync(path.join(repo, '.github', 'workflows', 'zeroplay-android-tv.yml'), 'utf8');
  ok(workflow.includes('ZeroPlay-TV-1.0.3-armeabi-v7a.apk') && !workflow.includes('1.0.2'),
    'CI workflow names and releases the 1.0.3 APK consistently');

  console.log('\n' + n + ' search/FastStream checks passed');
})().catch(e => { console.error(e); process.exit(1); });
