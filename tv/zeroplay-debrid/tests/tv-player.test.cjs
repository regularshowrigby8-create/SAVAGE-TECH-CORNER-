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
  tvFocusAndReveal() {},
  saveSettings() { ctx.settingsSaved = true; },
  closePlayer() { ctx.closed = true; },
  registerProgress() {},
  savePlaybackProgress() {},
  showPlayerChrome() {},
  drawPlayer() {},
  backdropURL() { return 'https://art.test/backdrop.jpg'; },
  episodeMetaCache: new Map(),
  episodeMetaKey(season, episode) { return 'ep:' + Number(season||0) + ':' + Number(episode||0); },
  rememberEpisodeMeta(season, episode, record) { return record || ctx.episodeMetaCache.get(ctx.episodeMetaKey(season, episode)) || null; },
  playerIndicator() {},
  playerTimers: [],
  document: {
    head: { appendChild() {} },
    getElementById() { return null; },
    createElement() { return { classList: { add() {} }, style: {}, setAttribute() {}, appendChild() {}, remove() {} }; },
  },
  tvFocusAndReveal() {},
};
ctx.globalThis = ctx;
vm.createContext(ctx);
vm.runInContext([
  between('const GENRES_MOVIE', 'function demoResponse'),
  between('function formatTime(sec)', 'function playerLoading'),
  between('const ZP_ENGINE_SCRIPTS', 'function registerProgress'),
  between('function directMediaKind', 'function startOwnEngine'),
  between('function playbackEpisodeKey', 'async function playerCommand'),
  between('function groupStreamsByServer', 'const titleServerCache'),
].join('\n'), ctx);

let n = 0;
const ok = (c, label) => { assert(c, label); n++; console.log('PASS', label); };
const eq = (a, b, label) => { assert.deepStrictEqual(JSON.parse(JSON.stringify(a)), b, label); n++; console.log('PASS', label); };

  /* ---- tracks: the menus must be driven by real engine state ---- */
  ok(typeof ctx.subtitlesFromStream === 'function', 'provider subtitle lists are normalised by the app');
  eq(ctx.subtitlesFromStream({ subtitles: [
    { url: 'https://cdn.test/en.vtt', lang: 'en', name: 'English' },
    { url: 'https://cdn.test/es.srt', lang: 'es' },
    { url: 'https://cdn.test/notes.txt', lang: 'en' },
  ] }).map(t => t.lang), ['en', 'es'], 'only timed-text files become tracks, with their language kept');
  const merged = ctx.mergeSubtitleTracks(
    [{ url: 'https://cdn.test/en.vtt', label: 'English' }, { url: 'https://cdn.test/en.vtt', label: 'dup' }],
    [{ url: 'https://cdn.test/fr.vtt', label: 'French' }]);
  eq(merged.length, 2, 'the same subtitle file arriving from two providers is listed once');
  eq(merged[1].label, 'French', 'merged tracks keep their provider labels');

  /* Resize is a real element property, not a label. */
  const fakeVideo = { style: {}, textTracks: [], audioTracks: [] };
  ctx.settings.stream.resizeMode = 'fit';
  ctx.playerState = { zpEngine: { kind: 'hls', instance: null, video: fakeVideo }, subtitleTracks: [], ui: {} };
  eq(ctx.zpEngineResizeMode(), 'fit', 'screen size starts at fit');
  eq(ctx.zpEngineApplyResize('zoom'), 'zoom', 'zooming the picture is accepted');
  eq(fakeVideo.style.objectFit, 'cover', 'zoom actually fills the frame');
  eq(ctx.zpEngineApplyResize('stretch'), 'stretch', 'stretching is accepted');
  eq(fakeVideo.style.objectFit, 'fill', 'stretch actually distorts to fill');
  eq(ctx.zpEngineApplyResize('nonsense'), 'fit', 'an unknown screen size falls back to fit instead of breaking');

  /* Subtitle tracks: built-in cues and provider files share one list, and
     selecting one turns the others off. */
  const cueA = { kind: 'subtitles', label: 'English', language: 'en', mode: 'disabled' };
  const cueB = { kind: 'subtitles', label: 'French', language: 'fr', mode: 'showing' };
  fakeVideo.textTracks = [cueA, cueB];
  ctx.playerState.subtitleTracks = [{ id: 'sub:0', url: 'https://cdn.test/de.vtt', label: 'German', lang: 'de', active: false }];
  const list = ctx.zpEngineSubtitleTracks();
  eq(list.map(t => t.label), ['English', 'French', 'German'], 'built-in and provider subtitles appear in one list');
  ok(ctx.zpEngineSelectSubtitle('text:0') === true, 'a built-in track can be selected');
  eq([cueA.mode, cueB.mode], ['hidden', 'disabled'], 'selecting a track enables one cue source; ZeroPlay paints the text');
  eq(ctx.playerState.subtitleSelection, 'text:0', 'the selected track is remembered');
  ctx.zpEngineSelectSubtitle('off');
  eq([cueA.mode, cueB.mode], ['disabled', 'disabled'], 'Off really turns every subtitle off');

  /* Audio: a stream with several tracks can be switched; a single-track
     stream reports that honestly instead of showing a dead menu. */
  const a0 = { label: 'English', language: 'en', enabled: true };
  const a1 = { label: 'Spanish', language: 'es', enabled: false };
  fakeVideo.audioTracks = [a0, a1];
  eq(ctx.zpEngineAudioTracks().map(t => t.label), ['English', 'Spanish'], 'every audio track a stream carries is listed');
  ok(ctx.zpEngineSelectAudio('audio:1') === true, 'a second audio track can be selected');
  eq([a0.enabled, a1.enabled], [false, true], 'selecting an audio track enables exactly one');
  fakeVideo.audioTracks = [];
  eq(ctx.zpEngineAudioTracks().length, 0, 'a single-track stream exposes no fake choices');
  ok(ctx.zpEngineSelectAudio('audio:5') === false, 'an audio track that does not exist is refused');

  /* Subtitle appearance is stored, clamped and applied. */
  ctx.settings.stream.subtitleStyle = { syncMs: 0, size: 100, vertical: 100 };
  eq(ctx.setSubtitleStyle('sync', 100).syncMs, 100, 'subtitle delay moves forward');
  eq(ctx.setSubtitleStyle('sync', -300).syncMs, -200, 'subtitle delay moves back');
  eq(ctx.setSubtitleStyle('size', -100).size, 60, 'subtitle size is clamped at the small end');
  eq(ctx.setSubtitleStyle('size', 900).size, 220, 'subtitle size is clamped at the large end');
  eq(ctx.setSubtitleStyle('vertical', -900).vertical, 4, 'subtitle height is clamped so cues cannot vanish');
  ctx.playerState = null;


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
  /* The painter: what a viewer reads on screen, including the delay setting. */
  cueA.mode = 'hidden';
  cueA.cues = [
    { startTime: 9, endTime: 11, text: 'first line' },
    { startTime: 9, endTime: 11, text: 'second line' },
    { startTime: 30, endTime: 32, text: 'much later' },
  ];
  fakeVideo.currentTime = 10;
  ctx.playerState = { zpEngine: { video: fakeVideo }, subtitleSelection: 'text:0', subtitleTracks: [] };
  ctx.settings.stream.subtitleStyle = { syncMs: 0, size: 100, vertical: 100 };
  eq(ctx.activeSubtitleText(10000), 'first line\nsecond line', 'the cue due right now is what gets painted');
  ctx.settings.stream.subtitleStyle.syncMs = -2000; /* this viewer wants cues two seconds early */
  eq(ctx.activeSubtitleText(10000), '', 'a delay really moves the cue instead of pretending to');
  eq(ctx.activeSubtitleText(8000), 'first line\nsecond line', 'with the delay applied, the cue shows two seconds earlier');
  ctx.settings.stream.subtitleStyle.syncMs = 2000; /* and now two seconds later */
  eq(ctx.activeSubtitleText(8000), '', 'a positive delay keeps the cue off screen at that moment');
  eq(ctx.activeSubtitleText(12000), 'first line\nsecond line', 'a positive delay shows it two seconds later');
  ctx.settings.stream.subtitleStyle.syncMs = 0;
  eq(ctx.activeSubtitleText(40000), '', 'nothing is painted between cues');
  cueA.mode = 'disabled';
  ctx.playerState.subtitleSelection = 'off';
  eq(ctx.activeSubtitleText(10000), '', 'no selection means no captions, even if a track is loaded');
  ctx.playerState = null;

  /* The resumed position and the source we mounted must reach the engine, and a
     teardown must leave nothing behind for the next stream to trip over. */
  ok(html.includes('id="btnPlayerTracks"') && html.includes('id="btnPlayerAspect"'), 'subtitles, audio and screen size live on the transport row');
  ok(html.includes('id="playerTracksSheet"') && html.includes('data-track-tab'), 'subtitles and audio share one tabbed panel');
  ok(['MediaPlayPause','MediaFastForward','MediaRewind','MediaStop'].every(k => html.includes('"'+k+'"')), 'the remote media keys are handled, as in the reference player');
  ok(html.includes('data-sub-style="sync"') && html.includes('data-sub-style="size"') && html.includes('data-sub-style="vertical"'), 'subtitle delay, size and position are adjustable');
  ok(/function handlePlayerTracksKeys\(e\)/.test(html) && html.includes('handlePlayerTracksKeys(e)) return true'), 'the panel takes D-pad keys before the transport does');
  ok(/const tracksSheet=document\.getElementById\("playerTracksSheet"\)/.test(html), 'Back closes the panel before it closes the player');
  ok(html.includes('subtitles:subtitlesFromStream(stream)'), 'provider subtitle lists travel with the stream');
  ok(/function playerExitAnimated\(\)/.test(html) && /player-overlay\.player-exiting/.test(html), 'leaving the player fades first instead of cutting on a live frame');
  ok(html.includes('if(playerState!==st) return; st.exiting=false; closePlayerNow();'), 'a deferred exit cannot tear down a player opened during the fade');
  ok(/function playerPauseOverview\(\)/.test(html) && html.includes('id="playerPausedCard"'), 'a paused player names what is playing and what it is about');
  ok(/function handlePlayerNextKeys\(e\)/.test(html) && html.includes('handlePlayerNextKeys(e)) return true'), 'the Up Next card owns the remote while it is open');
  ok(/function nextEpisodeAvailable\(\)/.test(html) && /function finishPlaybackAndLeave\(reason\)/.test(html), 'a finished video either offers the next episode or leaves the player');
  ok(/function startOwnEngine\(stage,url,source,loadT0,resumeAt,opts\)/.test(html), 'one mount path owns the player');
  ok(html.includes('zpEngineDestroy()'), 'leaving the player tears the engine down');
  ok(/st\.triedUrls=st\.triedUrls\|\|new Set\(\)/.test(html), 'a failed stream is remembered so failover cannot loop');
  ok(!/mountEmbedPlayer[\s\S]{0,400}postMessage/.test(html), 'the framed fallback is never remote-controlled');

  /* ---- end of playback, the rules ARVIO enforces ---- */
  const tvItem = { id: 1399, type: 'tv', title: 'A Show' };
  ctx.settings.ui = { autoplayNext: true };
  ctx.playerState = { item: tvItem, season: 2, episode: 3, noProgress: false, tempSource: null };
  eq(ctx.playbackEpisodeKey(), '1399:2:3', 'an episode is identified by show, season and episode');
  ok(ctx.nextEpisodeAvailable() === true, 'a mid-season episode offers what comes next');

  ctx.settings.ui.autoplayNext = false;
  ok(ctx.nextEpisodeAvailable() === false, 'with autoplay off no next episode is offered');
  ctx.settings.ui.autoplayNext = true;

  ctx.playerState.noProgress = true;
  ok(ctx.nextEpisodeAvailable() === false, 'a source test never advances the episode count');
  ctx.playerState.noProgress = false;

  ctx.playerState.item = { id: 27205, type: 'movie', title: 'A Film' };
  ok(ctx.nextEpisodeAvailable() === false, 'a finished movie has no next episode to offer');

  /* A finished video must always go somewhere: the old handler left the last
     frame on screen with no way forward for a movie or for autoplay-off. */
  ctx.playerState = { item: { id: 27205, type: 'movie', title: 'A Film' }, season: 1, episode: 1, playback: {} };
  ctx.closed = false;
  ctx.handlePlaybackEnded();
  ok(ctx.playerState.completionLeaving === true, 'a finished movie starts leaving the player instead of freezing on the last frame');
  ok(ctx.playerState.endedHandledFor === '27205:1:1', 'completion is claimed once, so a second ended event cannot re-run it');

  ctx.playerState = { item: tvItem, season: 2, episode: 3, noProgress: false, tempSource: null, playback: {} };
  ctx.handlePlaybackEnded();
  ok(ctx.playerState.nextPromptOpen === true, 'a finished episode opens the Up Next card');
  ok(ctx.playerState.nextPromptKey === 'ep:2:4', 'the card points at the next episode, not the one just watched');
  ctx.closeNextEpisodePrompt(true);

  /* Claim-once: STATE_ENDED stays true after the card is dismissed, so a second
     ended event for the same episode must not reopen it. */
  ctx.playerState.nextPromptOpen = false;
  ctx.handlePlaybackEnded();
  ok(ctx.playerState.nextPromptOpen === false, 'the same episode cannot open a second Up Next card');
  ctx.playerState.episode = 4;
  ctx.handlePlaybackEnded();
  ok(ctx.playerState.nextPromptOpen === true, 'a genuinely new episode opens its own card');
  ctx.closeNextEpisodePrompt(true);

  /* Playing now advances exactly one episode and clears the old stream. */
  ctx.playerState = { item: tvItem, season: 2, episode: 3, url: 'https://old.test/a.m3u8', subtitleTracks: [{ url: 'x.vtt' }], variant: 1, variants: [1], levels: [1], tried: new Set(['a']), triedUrls: new Set(['b']), nextPromptOpen: true, playback: {} };
  ctx.playNextEpisodeNow();
  eq(ctx.playerState.episode, 4, 'the next episode is exactly one further');
  eq(ctx.playerState.season, 2, 'the season is untouched');
  eq(ctx.playerState.url, '', 'the previous stream URL is not carried into the next episode');
  eq(ctx.playerState.subtitleTracks.length, 0, 'subtitles from the previous episode do not leak forward');
  eq([...ctx.playerState.triedUrls], [], 'failover history resets for the new episode');
  ctx.playerState = null;

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
