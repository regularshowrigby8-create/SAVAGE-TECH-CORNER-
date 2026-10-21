const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const path = require('path');

const webRoot = path.resolve(__dirname, '..');
const repoRoot = path.resolve(webRoot, '..', '..');
const html = fs.readFileSync(path.join(webRoot, 'index.html'), 'utf8');
const script = html.slice(html.indexOf('<script>') + 8, html.lastIndexOf('</script>'));

function between(start, end) {
  const a = script.indexOf(start);
  assert(a >= 0, 'missing start ' + start);
  const b = script.indexOf(end, a + start.length);
  assert(b > a, 'missing end ' + end);
  return script.slice(a, b);
}
function response(data, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() { return data; },
    async text() { return typeof data === 'string' ? data : JSON.stringify(data); },
  };
}
class FakeImage {
  constructor() {
    this.naturalWidth = 342;
    this.naturalHeight = 513;
    this.onload = null;
    this.onerror = null;
  }
  set src(value) {
    this._src = value;
    setTimeout(() => {
      if (String(value).includes('broken')) {
        if (this.onerror) this.onerror(new Error('broken test image'));
      } else if (this.onload) {
        this.onload();
      }
    }, 0);
  }
  get src() { return this._src || ''; }
}

const fetchCalls = [];
let cinemetaResult = null;
const ctx = {
  console,
  URL,
  URLSearchParams,
  Set,
  Map,
  WeakSet,
  Date,
  Math,
  Number,
  String,
  Array,
  Object,
  RegExp,
  Promise,
  Error,
  AbortController,
  setTimeout,
  clearTimeout,
  Image: FakeImage,
  ANDROID_TV_APP: true,
  CINEMETA: 'https://v3-cinemeta.strem.io',
  settings: {
    artwork: { enabled: true, cinemeta: true, tvmaze: true, anilist: true, itunes: true },
    ui: { tvPerformanceMode: true },
  },
  progress: {},
  watchlist: [],
  localStorage: { removeItem() {} },
  loadJSON(_key, fallback) { return fallback; },
  saveJSON() {},
  saveProgress() {},
  saveWatchlist() {},
  keyOf: (type, id) => `${type}:${id}`,
  esc: s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])),
  tvPerformanceEnabled: () => true,
  cmType: type => type === 'tv' ? 'series' : 'movie',
  lookupImdbByTmdb: () => '',
  async cinemetaFetch(url) {
    fetchCalls.push(String(url));
    return cinemetaResult;
  },
  async fetch(url, _opts) {
    const u = String(url);
    fetchCalls.push(u);
    if (u.includes('api.tvmaze.com/search/shows')) {
      return response([
        { show: { id: 999, name: 'Breaking Bad Copy', premiered: '1999-01-01', image: { medium: 'https://img.test/wrong.jpg' } } },
        { show: { id: 169, name: 'Breaking Bad', premiered: '2008-01-20', externals: { imdb: 'tt0903747' }, image: { medium: 'https://img.test/breaking-bad-medium.jpg', original: 'https://img.test/breaking-bad-original.jpg' } } },
      ]);
    }
    if (u.includes('api.tvmaze.com/lookup/shows')) return response({}, 404);
    if (u.includes('graphql.anilist.co')) {
      return response({ data: { Page: { media: [{
        seasonYear: 1999,
        title: { english: 'One Piece', romaji: 'One Piece', native: 'ワンピース' },
        synonyms: [],
        coverImage: { extraLarge: 'https://img.test/one-piece-xl.jpg', large: 'https://img.test/one-piece-large.jpg', medium: 'https://img.test/one-piece-medium.jpg' },
        bannerImage: 'https://img.test/one-piece-banner.jpg',
      }] } } });
    }
    if (u.includes('itunes.apple.com/search')) {
      return response({ results: [
        { trackName: 'Inception Copy', releaseDate: '1980-01-01', artworkUrl100: 'https://is1.mzstatic.com/image/thumb/wrong/100x100bb.jpg' },
        { trackName: 'Inception', releaseDate: '2010-07-16', artworkUrl100: 'https://is1.mzstatic.com/image/thumb/inception/100x100bb.jpg' },
      ] });
    }
    return response({}, 404);
  },
  // Mutable stubs used by the extracted source-readiness functions.
  __providerCounts: { installed: 2, candidate: 4, all: 6, streams: 2, websites: 1, projects: 1 },
  __readySources: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
  __discoveryCfg: { autoStart: true, stats: { cycles: 7, lastCycleAt: Date.now(), lastSummary: '2 new' } },
  providerCounts() { return ctx.__providerCounts; },
  allSources() { return ctx.__readySources; },
  discoveryCfg() { return ctx.__discoveryCfg; },
};
ctx.globalThis = ctx;
vm.createContext(ctx);
vm.runInContext([
  between('function titleOf(it)', 'function snap(it,type)'),
  between('function identityNormalizeTitle(value)', 'function identifyTitleCandidate(candidate, identity, options)'),
  between('const ARTWORK_CACHE_KEY=', 'function trailersAllowed()'),
  between('let discoveryRuntime=', 'function defaultDiscoveryConfig()'),
  between('function sourceReadiness()', 'function refreshDiscoveryUi()'),
  between('function sourceStatusPanelHTML()', 'function settingsHubHTML()'),
  between('function tvChooseDirectionalRecord(records,origin,direction)', 'function moveTvFocus(direction)'),
  'globalThis.__setDiscoveryRuntime = patch => Object.assign(discoveryRuntime, patch);',
].join('\n'), ctx, { filename: 'android-tv-extract.js' });

let passed = 0;
const ok = (condition, label) => { assert(condition, label); passed++; console.log('PASS', label); };
const eq = (actual, expected, label) => { assert.strictEqual(actual, expected, label); passed++; console.log('PASS', label); };

(async () => {
  const navRec = (id, left, top, w = 180, h = 260) => ({ el: { id }, left, right: left + w, top, bottom: top + h, x: left + w / 2, y: top + h / 2, w, h });
  const bottomCard = navRec('bottom', 520, 2200);
  eq(
    ctx.tvChooseDirectionalRecord([bottomCard, navRec('immediate-above', 40, 1880), navRec('aligned-far-above', 520, 1320)], bottomCard, 'up').el.id,
    'immediate-above',
    'Up from the bottom row selects the immediate row above instead of getting stuck',
  );
  eq(
    ctx.tvChooseDirectionalRecord([bottomCard, navRec('left', 300, 2200), navRec('right', 740, 2200), navRec('below', 520, 2520)], bottomCard, 'right').el.id,
    'right',
    'horizontal TV navigation stays on the same visual row',
  );
  eq(
    ctx.cleanArtworkUrl('https://images.metahub.space/poster/large/tt0903747/img.jpg', 'poster'),
    'https://images.metahub.space/poster/medium/tt0903747/img.jpg',
    'large MetaHub posters are right-sized to medium for TV',
  );
  eq(
    ctx.cleanArtworkUrl('https://images.metahub.space/background/large/tt0903747/img.jpg', 'backdrop'),
    'https://images.metahub.space/background/small/tt0903747/img.jpg',
    'TV performance mode requests the smaller MetaHub backdrop',
  );
  eq(
    ctx.cleanArtworkUrl('https://is1.mzstatic.com/image/thumb/show/100x100bb.jpg', 'poster'),
    'https://is1.mzstatic.com/image/thumb/show/342x513bb.jpg',
    'iTunes artwork is requested at poster-card size instead of thumbnail size',
  );
  eq(ctx.cleanArtworkUrl('https://example.test/no-poster-placeholder.jpg', 'poster'), '', 'provider placeholder images are rejected');

  const breakingBad = { id: 1396, media_type: 'tv', name: 'Breaking Bad', first_air_date: '2008-01-20' };
  ok(ctx.artworkCandidateMatches(['Breaking Bad'], ['2008-01-20'], breakingBad), 'artwork accepts an exact title/year match');
  ok(!ctx.artworkCandidateMatches(['Breaking Bad'], ['1999-01-01'], breakingBad), 'artwork rejects the same title from the wrong year');

  cinemetaResult = { meta: {
    poster: 'https://images.metahub.space/poster/small/tt0903747/img.jpg',
    background: 'https://images.metahub.space/background/large/tt0903747/img.jpg',
    logo: 'https://images.metahub.space/logo/medium/tt0903747/img.png',
    imdb_id: 'tt0903747',
  } };
  const cinemetaItem = { ...breakingBad, id: 9001, imdb_id: 'tt0903747' };
  const cinemetaArt = await ctx.resolveArtwork(cinemetaItem, { backdrop: true });
  eq(cinemetaArt.provider, 'Cinemeta / MetaHub', 'Cinemeta/MetaHub is the first keyless artwork provider');
  ok(cinemetaArt.poster.includes('/poster/medium/'), 'Cinemeta poster is browser-ready at medium size');
  eq(cinemetaItem.poster_url, cinemetaArt.poster, 'resolved Cinemeta poster is applied to the catalogue item');

  cinemetaResult = { meta: null };
  const tvmazeItem = { ...breakingBad, id: 9002 };
  const beforeTvmazeFetch = fetchCalls.length;
  const tvmazeArt = await ctx.resolveArtwork(tvmazeItem, {});
  eq(tvmazeArt.provider, 'TVmaze', 'TVmaze supplies series artwork when Cinemeta has none');
  eq(tvmazeArt.poster, 'https://img.test/breaking-bad-medium.jpg', 'TV performance mode chooses TVmaze medium artwork');
  ok(fetchCalls.slice(beforeTvmazeFetch).some(u => u.includes('api.tvmaze.com/search/shows')), 'TVmaze public search requires no user key');

  ctx.settings.artwork.cinemeta = false;
  ctx.settings.artwork.tvmaze = false;
  const animeItem = {
    id: 9003,
    media_type: 'tv',
    name: 'One Piece',
    first_air_date: '1999-10-20',
    genre_ids: [16],
    original_language: 'ja',
    origin_country: ['JP'],
  };
  const anilistArt = await ctx.resolveArtwork(animeItem, { backdrop: true });
  eq(anilistArt.provider, 'AniList', 'AniList supplies anime artwork without user authentication');
  eq(anilistArt.poster, 'https://img.test/one-piece-large.jpg', 'AniList large cover is the constrained-TV rendition');
  eq(anilistArt.backdrop, 'https://img.test/one-piece-banner.jpg', 'AniList banner fills the wide TV surface');

  ctx.settings.artwork.anilist = false;
  const inception = { id: 9004, media_type: 'movie', title: 'Inception', release_date: '2010-07-16' };
  const itunesArt = await ctx.resolveArtwork(inception, {});
  eq(itunesArt.provider, 'Apple iTunes', 'iTunes is a keyless final artwork fallback');
  ok(itunesArt.poster.endsWith('/342x513bb.jpg'), 'iTunes fallback returns a usable poster rendition');

  ctx.settings.artwork.enabled = false;
  eq(await ctx.resolveArtwork({ id: 9005, media_type: 'movie', title: 'Disabled', release_date: '2020-01-01' }, {}), null, 'turning artwork fallback off makes no provider request');
  ctx.settings.artwork.enabled = true;
  ctx.settings.artwork.cinemeta = true;
  ctx.settings.artwork.tvmaze = true;
  ctx.settings.artwork.anilist = true;

  cinemetaResult = { meta: { poster: 'https://img.test/broken-poster.jpg', background: '', imdb_id: 'tt0903747' } };
  const brokenItem = { ...breakingBad, id: 9006, imdb_id: 'tt0903747' };
  const recoveredArt = await ctx.resolveArtwork(brokenItem, {});
  eq(recoveredArt.provider, 'TVmaze', 'a broken provider image is probed out and the next provider is tried');

  ctx.__setDiscoveryRuntime({ cycleRunning: false, running: true, lastFoundAt: 0, lastFoundCount: 0, lastError: '' });
  eq(ctx.sourceReadiness().label, 'Ready', 'source status simplifies to Ready');
  ctx.__setDiscoveryRuntime({ cycleRunning: true });
  eq(ctx.sourceReadiness().label, 'Searching', 'active lookup simplifies to Searching');
  ctx.__setDiscoveryRuntime({ cycleRunning: false, lastFoundAt: Date.now(), lastFoundCount: 2 });
  eq(ctx.sourceReadiness().label, 'Found sources', 'a recent successful lookup says Found sources');
  ctx.__readySources = [];
  eq(ctx.sourceReadiness().label, 'Problem', 'no enabled sources says Problem instead of zero counters');
  const normalStatus = ctx.discoveryStatusText();
  ok(!/cycle|identified\/installed|awaiting identification|total providers|last never|registry cap/i.test(normalStatus), 'normal source status contains no engineering counters');
  ok(/Diagnostics:/.test(ctx.discoveryDiagnosticsText()), 'detailed source counters remain available only as diagnostics');
  ctx.__readySources = [{ id: 'a' }];
  ctx.__setDiscoveryRuntime({ lastFoundAt: 0, lastFoundCount: 0 });
  const panel = ctx.sourceStatusPanelHTML();
  ok(panel.includes('btnSourceStateSearch') && panel.includes('Find more') && panel.includes('Advanced'), 'source panel offers one primary action and one Advanced door');
  ok(!/awaiting identification|registry|total providers|cycle/i.test(panel), 'source panel does not expose registry language');

  const androidJavaPath = path.join(repoRoot, 'tv/android-tv/app/src/main/java/com/savage/zeroplay/tv/MainActivity.java');
  const androidManifestPath = path.join(repoRoot, 'tv/android-tv/app/src/main/AndroidManifest.xml');
  const androidGradlePath = path.join(repoRoot, 'tv/android-tv/app/build.gradle.kts');
  const androidJava = fs.readFileSync(androidJavaPath, 'utf8');
  const androidManifest = fs.readFileSync(androidManifestPath, 'utf8');
  const androidGradle = fs.readFileSync(androidGradlePath, 'utf8');
  const indexDetection = html.indexOf('const ANDROID_TV_APP=');
  const indexSettings = html.indexOf('settings.stream=Object.assign');
  const staticChecks = [
    [indexDetection >= 0 && indexSettings > indexDetection, 'Android TV detection happens before web settings defaults'],
    [html.includes('ZeroPlayTV\\/') && html.includes('window.ZeroPlayAndroidTV'), 'native user-agent and bridge both activate TV mode'],
    [html.includes('settings.artwork=Object.assign({enabled:true,cinemeta:true,tvmaze:true,anilist:true,itunes:true}'), 'four artwork providers are ready with zero user configuration'],
    [!html.includes('settings.tmdbKey') && !html.includes('settings.artworkKey'), 'no user-entered artwork/TMDB API key exists'],
    [html.includes('ANDROID_TV_APP&&resumeItems.length?resumeHTML+heroHTML'), 'Android TV puts instant resume before the featured hero'],
    [html.includes('Couch-first Android TV') && html.includes('flex-basis:clamp(430px,34vw,570px)'), 'TV home uses large artwork-first rows instead of dashboard density'],
    [html.includes('body.android-tv .menu-category-list') && html.includes('body.android-tv .filters'), 'text-heavy category/filter chrome is removed from the TV normal path'],
    [html.includes('function applyTvSourcesLayout') && html.includes('"sourceStatePanel","settingsAddWebsite","settingsPlaybackSources"'), 'TV Sources opens as status plus two useful panels, not every control'],
    [html.includes('Advanced &amp; diagnostics') && html.includes('id="discoveryDiagnostics"'), 'source counters are contained in an Advanced diagnostics disclosure'],
    [html.includes('featureSwitchHTML("discovery","Find sources automatically"') && !html.includes('featureSwitchHTML("dnsWarm","Connect faster","Prepare source connections in advance.","menu")'), 'menu quick settings keep engineering switches out'],
    [html.includes('function handleZeroPlayTvBack') && html.includes('function handleZeroPlayTvMediaKey') && html.includes('function handleZeroPlayTvLowMemory'), 'web app exposes the native remote and low-memory contract'],
    [androidManifest.includes('LEANBACK_LAUNCHER') && androidManifest.includes('android.software.leanback') && androidManifest.includes('@drawable/tv_banner'), 'native app is discoverable as an Android TV Leanback app'],
    [androidGradle.includes('include("armeabi-v7a")') && androidGradle.includes('isUniversalApk = false') && androidGradle.includes('applicationId = "com.savage.zeroplay.tv"'), 'native release is explicitly scoped to the Xiaomi box ARMv7 userspace'],
    [androidJava.includes('KEYCODE_MEDIA_PLAY_PAUSE') && androidJava.includes('handleZeroPlayTvBack') && androidJava.includes('ZeroPlayTV/'), 'native shell forwards D-pad/media keys and identifies itself to the web app'],
    [androidJava.includes('onTrimMemory') && androidJava.includes('handleZeroPlayTvLowMemory') && androidJava.includes('totalMemoryMb'), 'native shell reports memory and asks the web app to shed previews under pressure'],
    [fs.existsSync(path.join(repoRoot, 'tv/android-tv/app/src/main/res/drawable-xhdpi/tv_banner.png')) && fs.existsSync(path.join(repoRoot, 'tv/android-tv/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png')), 'TV launcher banner and icons are present'],
    [html.includes('tabindex="0" data-switch-label="1"') && html.includes('closest("[data-switch-label]")'), 'menu/settings switches are D-pad focusable and toggle with OK so menus can scroll'],
    [html.includes('lastTvFocusPoint') && html.includes('function tvChooseDirectionalRecord') && html.includes('function tvRevealElement') && html.includes('getClientRects'), 'lost TV focus recovers near the last position with one-pass document geometry'],
    [html.includes('records.filter(r=>rail.contains(r.el))') && html.includes('if(movedTv||ANDROID_TV_APP) e.preventDefault()') && html.includes('moveTvFocusFromKey'), 'rail left/right stays in-row, repeats are coalesced, and TV arrows never fall through to native WebView focus jumps'],
    [html.includes('function hidePlayerChrome') && html.includes('function schedulePlayerChromeHide') && html.includes('overlayEl.classList.contains("chrome-hidden")'), 'player menu auto-hides during TV playback and wakes on the next key press'],
    [html.includes('function setEmbedFocusMode') && html.includes('function focusEmbeddedPlayer') && androidJava.includes('setTvCursorMode') && androidJava.includes('TvCursorView'), 'embedded players get a native D-pad cursor/focus mode and Back returns to the ZeroPlay bar first'],
    [html.includes('function armProviderFrame') && html.includes('allow-scripts allow-same-origin') && androidJava.includes('shouldInterceptRequest') && androidJava.includes('isScamUrl') && androidJava.includes('isTrustedAppHost'), 'provider frames are sandboxed and QR/robot-check redirects are blocked natively'],
    [html.includes('if(key==="Enter"||key==="NumpadEnter")') && html.includes('handleZeroPlayBlockedScam'), 'remote OK drives the native player and blocked scam pages are handled explicitly'],
    [html.includes('autoHighLevelId') && html.includes('autoHighBufferingSince'), 'FastStream auto quality starts at the highest level and steps down only on sustained buffering'],
    [html.includes('body.android-tv .settings-hub-grid{grid-template-columns:1fr}') && html.includes('body.android-tv .modal{width:min(980px,94vw)') && html.includes('#btnPlayerDownload'), 'TV layout keeps settings on-screen and hides tool clutter from the player bar'],
    [html.includes('body.android-tv *{backdrop-filter:none!important;') && html.includes('animation-duration:.12s!important'), 'TV mode strips backdrop blur and caps animation cost for 2 GB boxes'],
  ];
  for (const [condition, label] of staticChecks) ok(condition, label);

  console.log(`\n${passed} Android TV/UI/artwork checks passed`);
})().catch(err => { console.error(err); process.exit(1); });
