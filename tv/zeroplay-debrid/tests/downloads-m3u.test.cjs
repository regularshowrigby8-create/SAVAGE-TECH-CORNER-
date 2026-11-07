const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const path = require('path');
const { createCipheriv } = require('crypto');

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

let passed = 0;
function ok(cond, label) { assert(cond, label); passed++; console.log('PASS', label); }
function eq(a, b, label) {
  assert.deepStrictEqual(JSON.parse(JSON.stringify(a)), b, `${label}: ${JSON.stringify(a)} !== ${JSON.stringify(b)}`);
  passed++; console.log('PASS', label);
}
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function waitFor(fn, label, timeout = 3000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    if (fn()) return;
    await sleep(20);
  }
  assert.fail('timed out waiting for ' + label);
}
const enc = new TextEncoder();
const dec = new TextDecoder();
const bytesOf = value => typeof value === 'string' ? enc.encode(value) : new Uint8Array(value);
function bufferOf(value) {
  const b = bytesOf(value);
  return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
}
class FakeHeaders {
  constructor(init = {}) {
    this.map = new Map(Object.entries(init).map(([k, v]) => [String(k).toLowerCase(), String(v)]));
  }
  get(name) { return this.map.get(String(name).toLowerCase()) || null; }
}
function fakeResponse({ status = 200, headers = {}, body = '', chunks = null, url = '' } = {}) {
  const all = bytesOf(body);
  const parts = (chunks || [all]).map(bytesOf);
  let cancelled = false;
  return {
    status,
    ok: status >= 200 && status < 300,
    url,
    headers: new FakeHeaders(headers),
    body: {
      async cancel() { cancelled = true; },
      getReader() {
        let i = 0;
        return {
          async read() { return i < parts.length ? { done: false, value: parts[i++] } : { done: true, value: undefined }; },
          async cancel() { cancelled = true; },
        };
      },
    },
    get cancelled() { return cancelled; },
    async arrayBuffer() { return bufferOf(all); },
    async text() { return dec.decode(all); },
  };
}
function rangeSliceResponse(allBytes, rangeHeader, contentType = 'video/mp4') {
  const m = String(rangeHeader || '').match(/bytes=(\d+)-(\d*)/);
  assert(m, 'expected a Range header, got ' + rangeHeader);
  const start = Number(m[1]);
  const end = m[2] === '' ? allBytes.length - 1 : Number(m[2]);
  const slice = allBytes.slice(start, end + 1);
  return fakeResponse({
    status: 206,
    body: slice,
    headers: {
      'content-type': contentType,
      'content-length': String(slice.length),
      'content-range': `bytes ${start}-${end}/${allBytes.length}`,
      'accept-ranges': 'bytes',
    },
  });
}

const toasts = [];
const toggledClasses = [];
let stopCardPreviewCalls = 0;
let warmAllSourcesCalls = 0;
let discoveryStarts = 0;
let discoveryStops = 0;
const context = {
  console, URL, URLSearchParams, Set, Map, Date, Math, Number, String, Array, Object, RegExp,
  Promise, Error, JSON, Uint8Array, ArrayBuffer, DataView, TextEncoder, TextDecoder,
  Blob, AbortController, performance, crypto: globalThis.crypto,
  setTimeout, clearTimeout, setInterval, clearInterval,
  location: { href: 'https://zeroplay.test/', hash: '#/home' },
  navigator: { connection: {} },
  document: {
    body: { classList: { toggle(name, on) { toggledClasses.push([name, !!on]); }, contains() { return false; }, add() {}, remove() {} } },
    addEventListener() {},
    querySelector() { return null; },
    querySelectorAll() { return []; },
    getElementById() { return null; },
    createElement(tag) { return tag === 'video' ? { canPlayType() { return ''; } } : {}; },
  },
  settings: {
    source: 'vidstuck', disabledSources: [], customSources: [], sites: [], addons: [], providers: [],
    providerTombstones: {}, health: {}, dlog: [],
    stream: {
      quality: '720p', preload: 'aggressive', preloadLastMode: 'aggressive', player: 'auto',
      raceTimeoutSec: 999, stallTimeoutSec: 1, primeThreads: 99, primeChunkKb: 1,
      primeBudgetMb: 99, bufferTargetSec: 999, maxFileSizeGb: 5, dataSaver: 'auto',
      dnsWarm: false, sortByHealth: true, trailerPreviews: true, volume: .9, muted: false, rate: 1,
      autoFailover: true, stallSwitch: true, embedwaveFailover: false,
      fastStreamApiProbe: true, nextEpisodeWarm: true,
    },
    ui: { autoplayNext: true, reduceMotion: false, compactCards: false, showCategoryStrip: true },
    downloads: {
      enabled: true, multithreaded: true, threads: 99, chunkMb: .01, concurrent: 99,
      m3uExtraction: true, autoSave: false, autoResume: false, refreshExpired: true, keepCompleted: true,
    },
    discovery: { autoStart: false, crawlers: {}, cursors: {}, seeds: [], stats: {}, githubQueries: [] },
    feed: {},
  },
  watchlist: [],
  progress: {},
  playerState: null,
  esc: s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])),
  $$(selector, rootEl) {
    const from = rootEl && rootEl.querySelectorAll ? rootEl.querySelectorAll(selector) : this.document.querySelectorAll(selector);
    return [...from];
  },
  saveSettings() {},
  toast(msg, type) { toasts.push({ msg: String(msg), type: type || '' }); },
  stopCardPreview() { stopCardPreviewCalls++; },
  stopBackdropPreview() {},
  warmAllSources() { warmAllSourcesCalls++; },
  startDiscoveryEngine() { discoveryStarts++; context.settings.discovery.autoStart = true; },
  stopDiscoveryEngine() { discoveryStops++; context.settings.discovery.autoStart = false; },
  refreshDiscoveryUi() {},
  trailerKeyFromData() { return null; },
  catalogItemFor(type, id) {
    return { id: Number(id), media_type: type, title: type === 'movie' ? 'Catalogue title' : undefined, name: type === 'tv' ? 'Catalogue title' : undefined, trailer_key: 'test-key' };
  },
  async findTitleServers() { return { options: [] }; },
  async resolverCall() { throw new Error('resolver not configured in this test'); },
  async fetch() { throw new Error('fetch not configured in this test'); },
};
context.window = context;
context.globalThis = context;
vm.createContext(context);
vm.runInContext([
  between('function titleOf(it)', '/* ---------------- built-in title identifier'),
  between('/* ---------------- built-in title identifier', '/* ---------------- demo catalog'),
  between('const CAP_GB_HARD', 'async function probeFileSize'),
  between('function formatTime(sec)', 'function playerLoading'),
  between('function directMediaKind(url)', 'function nativeCanPlayDirect'),
  between('function cleanHttpUrl(raw)', 'function manifestBaseFromUrl'),
  between('/* ---------------- downloads + M3U extraction', '/* ---------------- detail modal'),
].join('\n'), context, { filename: 'downloads-extracted.js' });

function manualJob(overrides = {}) {
  return Object.assign({
    id: 'job-' + Math.random().toString(36).slice(2, 9),
    title: 'Test title', ctx: { type: 'movie', id: 1, title: 'Test title' },
    url: 'https://cdn.test/file.mp4', quality: '720p', serverName: 'Test server',
    status: 'paused', createdAt: Date.now(), updatedAt: Date.now(),
    downloadedBytes: 0, totalBytes: null, speedBps: 0, error: '', note: '',
    threads: 1, partSizes: {}, completedChunks: [], fileStored: false,
    chunksStored: true, retries: 0, filename: 'Test title.mp4', mime: 'video/mp4',
  }, overrides);
}

(async () => {
  /* ---------- settings clamps and shared switches ---------- */
  const st = context.streamCfg();
  eq([st.raceTimeoutSec, st.stallTimeoutSec, st.primeThreads, st.primeChunkKb, st.primeBudgetMb, st.bufferTargetSec],
    [60, 30, 4, 64, 8, 60], 'stream/FastStream numeric settings are clamped');
  const dl = context.downloadCfg();
  eq([dl.threads, dl.chunkMb, dl.concurrent], [8, .25, 3], 'download numeric settings are clamped');
  context.settings.downloads.threads = 2;
  context.settings.downloads.chunkMb = .25;
  context.settings.downloads.concurrent = 1;

  context.setFeatureToggle('trailers', false);
  ok(context.settings.stream.trailerPreviews === false && stopCardPreviewCalls === 1,
    'trailer switch persists off and stops the active preview');
  context.setFeatureToggle('preload', false);
  eq([context.settings.stream.preload, context.settings.stream.preloadLastMode], ['off', 'aggressive'],
    'preload switch remembers aggressive mode while off');
  context.setFeatureToggle('preload', true);
  eq(context.settings.stream.preload, 'aggressive', 'preload switch restores the previous non-off mode');
  context.setFeatureToggle('dnsWarm', true);
  await sleep(10);
  ok(warmAllSourcesCalls === 1, 'DNS warm-up switch calls the real source warmer');
  context.setFeatureToggle('discovery', true);
  context.setFeatureToggle('discovery', false);
  eq([discoveryStarts, discoveryStops], [1, 1], 'continuous-adding switch starts and stops the engine');
  ok(context.getFeatureToggle('embedwaveFailover') === false, 'Embedwave automatic failover is off by default');
  context.setFeatureToggle('embedwaveFailover', true);
  ok(context.getFeatureToggle('embedwaveFailover') === true, 'Embedwave failover has its own explicit switch');
  context.setFeatureToggle('embedwaveFailover', false);

  const switchHtml = context.featureSwitchHTML('multithreadedDownloads', 'Multithreaded downloads', 'Parallel where supported', 'test');
  ok(switchHtml.includes('data-feature-toggle="multithreadedDownloads"') && switchHtml.includes('checked'),
    'shared feature-switch markup reflects persisted state');
  const toggleKeys = [...new Set([...html.matchAll(/featureSwitchHTML\("([^"]+)"/g)].map(m => m[1]))];
  const requiredKeys = ['discovery', 'trailers', 'autoFailover', 'stallSwitch', 'embedwaveFailover', 'preload',
    'fastStreamProbe', 'nextEpisodeWarm', 'dnsWarm', 'sortByHealth', 'downloads', 'multithreadedDownloads',
    'm3uExtraction', 'downloadAutoSave', 'downloadAutoResume', 'refreshExpiredDownloads',
    'keepCompletedDownloads', 'autoplayNext', 'reduceMotion', 'compactCards', 'categoryStrip'];
  ok(requiredKeys.every(k => toggleKeys.includes(k)), 'menu/settings expose separate switches for every optional feature');

  /* ---------- client M3U parsing ---------- */
  const master = `#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
720/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
1080/index.m3u8
`;
  const media720 = `#EXTM3U
#EXT-X-VERSION:6
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:40
#EXT-X-MAP:URI="init-1.mp4"
#EXTINF:6,
seg-40.m4s
#EXT-X-DISCONTINUITY
#EXT-X-MAP:URI="init-2.mp4"
#EXTINF:5.5,
seg-41.m4s
#EXT-X-ENDLIST
`;
  const parsedMaster = context.parseM3uPlaylist(master, 'https://cdn.test/master.m3u8');
  ok(parsedMaster.isMaster && parsedMaster.variants.length === 2, 'client identifies an M3U master playlist');
  eq(context.chooseM3uVariant(parsedMaster.variants, '720p').url, 'https://cdn.test/720/index.m3u8',
    'client chooses the requested M3U quality variant');
  const parsedMedia = context.parseM3uPlaylist(media720, 'https://cdn.test/720/index.m3u8');
  eq(parsedMedia.parts.map(p => p.url.split('/').pop()), ['init-1.mp4', 'seg-40.m4s', 'init-2.mp4', 'seg-41.m4s'],
    'multiple HLS init maps stay in playback/assembly order');
  eq(parsedMedia.segments.map(s => s.sequence), [40, 41], 'HLS media sequence is retained for AES IV derivation');

  const ranged = context.parseM3uPlaylist(`#EXTM3U
#EXTINF:4,
#EXT-X-BYTERANGE:3@2
all.bin
#EXTINF:4,
#EXT-X-BYTERANGE:2
all.bin
#EXT-X-ENDLIST
`, 'https://cdn.test/ranged.m3u8');
  eq(ranged.segments.map(s => [s.byterange.start, s.byterange.end]), [[2, 4], [5, 6]],
    'implicit HLS byte ranges continue after the previous range');

  context.fetch = async url => {
    if (String(url).endsWith('/master.m3u8')) return fakeResponse({ body: master, url: String(url) });
    if (String(url).endsWith('/720/index.m3u8')) return fakeResponse({ body: media720, url: String(url) });
    throw new Error('unexpected playlist fetch ' + url);
  };
  const plan = await context.resolveHlsPlan('https://cdn.test/master.m3u8', '720p');
  eq([plan.playlistUrl, plan.parts.length, Math.round(plan.duration)], ['https://cdn.test/720/index.m3u8', 4, 12],
    'master M3U resolves to the selected media playlist plan');

  const extracted = context.extractM3uUrls('links "https:\\/\\/cdn.test\\/a.m3u8?token=1\\u0026x=2" and /relative/b.m3u8', 'https://site.test/watch/1');
  eq(extracted, ['https://cdn.test/a.m3u8?token=1&x=2', 'https://site.test/relative/b.m3u8'],
    'M3U extractor unescapes and resolves playlist links');
  eq(context.m3uResultHTML({ url: 'https://zeroplay.test/', label: 'Pasted', localOnly: true }, 0).includes('data-m3u-action'), false,
    'pasted local-only playlist does not pretend the app URL is downloadable');

  /* ---------- AES-128 HLS segment decryption ---------- */
  const keyBytes = Buffer.from('00112233445566778899aabbccddeeff', 'hex');
  const iv = Buffer.alloc(16); iv.writeUInt32BE(7, 12);
  const cipher = createCipheriv('aes-128-cbc', keyBytes, iv);
  const encrypted = Buffer.concat([cipher.update(Buffer.from('secret segment')), cipher.final()]);
  context.fetch = async url => {
    assert(String(url).endsWith('/key.bin'));
    return fakeResponse({ body: keyBytes });
  };
  const decrypted = await context.decryptHlsSegment(manualJob(), {
    sequence: 7,
    key: { method: 'AES-128', uri: 'https://cdn.test/key.bin', iv: '' },
  }, bufferOf(encrypted), new AbortController().signal);
  eq(dec.decode(new Uint8Array(decrypted)), 'secret segment', 'AES-128 HLS segment decrypts with sequence-derived IV');

  /* ---------- multithreaded byte-range resume ---------- */
  const fileBytes = bytesOf('Hello world');
  const rangeJob = manualJob({ kind: 'file', totalBytes: fileBytes.length, chunkSize: 4, chunksTotal: 3 });
  await context.saveDownloadPart(rangeJob, 0, bufferOf(fileBytes.slice(0, 4)));
  const requestedRanges = [];
  context.fetch = async (url, opts) => {
    requestedRanges.push(opts.headers.Range);
    return rangeSliceResponse(fileBytes, opts.headers.Range);
  };
  context.settings.downloads.multithreaded = true;
  context.settings.downloads.threads = 2;
  eq(context.effectiveDownloadThreads(rangeJob), 2, 'existing jobs follow the current multithreaded thread setting');
  await context.runRangeDownload(rangeJob, new AbortController().signal);
  eq(requestedRanges.slice().sort(), ['bytes=4-7', 'bytes=8-10'], 'range resume downloads only missing chunks');
  eq(await (await context.assembleDownloadBlob(rangeJob)).text(), 'Hello world', 'stored range chunks assemble in order');

  const failedRangeJob = manualJob({ kind: 'file', totalBytes: fileBytes.length, chunkSize: 4, chunksTotal: 3 });
  context.fetch = async (url, opts) => {
    if (opts.headers.Range === 'bytes=4-7') return fakeResponse({ status: 404, body: 'missing' });
    return rangeSliceResponse(fileBytes, opts.headers.Range);
  };
  await assert.rejects(() => context.runRangeDownload(failedRangeJob, new AbortController().signal), /HTTP 404/);
  ok(context.completedDownloadSet(failedRangeJob).has(0) && context.completedDownloadSet(failedRangeJob).has(2)
    && !context.completedDownloadSet(failedRangeJob).has(1),
    'parallel range workers settle and preserve successful chunks after one failure');

  /* ---------- sequential stream resume and integrity ---------- */
  const streamJob = manualJob({ kind: 'stream', chunksTotal: 0 });
  await context.saveDownloadPart(streamJob, 0, bufferOf('Hello'));
  let streamRange = '';
  context.fetch = async (url, opts) => {
    streamRange = opts.headers.Range || '';
    return fakeResponse({
      status: 206,
      headers: { 'content-range': 'bytes 5-10/11', 'content-length': '6', 'content-type': 'video/mp4' },
      chunks: [' wor', 'ld'],
    });
  };
  await context.runStreamDownload(streamJob, new AbortController().signal);
  eq(streamRange, 'bytes=5-', 'sequential download resumes from stored bytes');
  eq(await (await context.assembleDownloadBlob(streamJob)).text(), 'Hello world', 'sequential resume assembles the complete file');

  const wrongOffsetJob = manualJob({ kind: 'stream', chunksTotal: 0 });
  await context.saveDownloadPart(wrongOffsetJob, 0, bufferOf('Hello'));
  context.fetch = async () => fakeResponse({
    status: 206,
    headers: { 'content-range': 'bytes 3-10/11', 'content-length': '8' },
    body: 'lo world',
  });
  await assert.rejects(() => context.runStreamDownload(wrongOffsetJob, new AbortController().signal), /different resume offset/);
  ok(true, 'sequential resume rejects a mismatched Content-Range instead of corrupting the file');

  const gapJob = manualJob({ kind: 'stream', chunksTotal: 0 });
  await context.saveDownloadPart(gapJob, 0, bufferOf('Hell'));
  await context.saveDownloadPart(gapJob, 2, bufferOf('ld'));
  let gapRange = 'not-called';
  context.fetch = async (url, opts) => {
    gapRange = opts.headers.Range || '';
    return fakeResponse({ status: 200, headers: { 'content-length': '11', 'content-type': 'video/mp4' }, body: 'Hello world' });
  };
  await context.runStreamDownload(gapJob, new AbortController().signal);
  ok(gapRange === '' && /not contiguous/.test(gapJob.note), 'non-contiguous sequential parts restart safely');
  eq(await (await context.assembleDownloadBlob(gapJob)).text(), 'Hello world', 'restarted sequential download is not spliced from gapped parts');

  /* ---------- HLS byte-range over-response slicing ---------- */
  const hlsBytesJob = manualJob({
    kind: 'hls', mime: 'video/mp2t', filename: 'segment.ts', chunksTotal: 1,
    segments: [{ url: 'https://cdn.test/all.bin', byterange: { start: 2, end: 4, length: 3 }, duration: 4, sequence: 0, key: null }],
  });
  context.fetch = async () => fakeResponse({ status: 200, body: '0123456' });
  await context.runHlsDownload(hlsBytesJob, new AbortController().signal);
  eq(await (await context.assembleDownloadBlob(hlsBytesJob)).text(), '234',
    'HLS byte-range HTTP 200 response is sliced before storage');

  /* ---------- hard cap while storing unknown-size parts ---------- */
  const oldCap = context.settings.stream.maxFileSizeGb;
  context.settings.stream.maxFileSizeGb = 0.00000001; // 10 bytes through the real capGb()/capBytes() path
  const capJob = manualJob({ kind: 'stream', chunksTotal: 0 });
  await context.saveDownloadPart(capJob, 0, bufferOf('12345678'));
  await assert.rejects(() => context.saveDownloadPart(capJob, 1, bufferOf('123')), /exceeds the .* GB cap/);
  ok(true, 'unknown-size downloads stop at the configured cap before storing more data');
  context.settings.stream.maxFileSizeGb = oldCap;

  /* ---------- create -> pause -> duplicate click resumes -> complete ---------- */
  const identity = context.buildTitleIdentity({ type: 'movie', id: 27205, title: 'Inception', year: '2010', imdb: 'tt1375666' });
  const dlCtx = {
    type: 'movie', id: 27205, title: 'Inception', year: '2010', season: 1, episode: 1,
    imdb: 'tt1375666', identity,
    item: { id: 27205, type: 'movie', title: 'Inception', poster_path: null, backdrop_path: null, year: '2010', rating: null, poster_url: null, backdrop_url: null, imdb_id: 'tt1375666', trailer_key: null, logo_url: null, genre_ids: [], overview: '' },
  };
  let hangDownload = true;
  let downloadStarted = false;
  context.fetch = (url, opts = {}) => {
    const range = (opts.headers || {}).Range || '';
    if (range === 'bytes=0-0') return Promise.resolve(rangeSliceResponse(fileBytes, range));
    if (hangDownload) {
      downloadStarted = true;
      return new Promise((resolve, reject) => {
        const signal = opts.signal;
        if (!signal) return;
        signal.addEventListener('abort', () => {
          const e = new Error('aborted'); e.name = 'AbortError'; reject(e);
        }, { once: true });
      });
    }
    return Promise.resolve(rangeSliceResponse(fileBytes, range));
  };
  const created = await context.createDownloadJob({ url: 'https://cdn.test/inception.mp4', quality: '1080p', bytes: 11 }, dlCtx, 'Direct test');
  await waitFor(() => downloadStarted && created.status === 'running', 'created download to start');
  context.pauseDownload(created, true);
  await waitFor(() => created.status === 'paused', 'download to pause');
  hangDownload = false;
  const duplicate = await context.createDownloadJob({ url: 'https://cdn.test/inception.mp4', quality: '1080p', bytes: 11 }, dlCtx, 'Direct test');
  eq(duplicate.id, created.id, 'clicking Download again finds the existing exact-title job');
  await waitFor(() => created.status === 'complete', 'existing paused download to resume and complete');
  eq(await (await context.assembleDownloadBlob(created)).text(), 'Hello world', 'pause/resume lifecycle produces the complete file');

  const statsHtml = context.downloadStatsHTML(created);
  ok(statsHtml.includes('Parallel byte ranges') && statsHtml.includes('2 threads'),
    'partial download progress refresh keeps mode and thread stats');

  const deleteJob = manualJob({ kind: 'stream', status: 'paused' });
  context.downloadJobs?.set?.(deleteJob.id, deleteJob); // no-op if the VM lexical map is not exposed
  await context.saveDownloadPart(deleteJob, 0, bufferOf('data'));
  context.persistDownloadJob(deleteJob, true);
  await sleep(20);
  await context.deleteDownload(deleteJob, { silent: true });
  context.persistDownloadJob(deleteJob, true);
  await sleep(20);
  eq(await context.dlGet('jobs', deleteJob.id), null, 'deleted download cannot be resurrected by a late persist');

  /* ---------- server resolver M3U/text modes ---------- */
  const server = require(path.join(root, 'api/extract.js'));
  const netlifyServer = require(path.join(root, 'netlify/functions/extract.js'));
  const parsedPage = server._parse.extractM3u(
    '<html><head><title>Watch</title></head><script>var src="https:\\/\\/cdn.test\\/movie.m3u8?x=1\\u0026y=2";</script><video src="/local.m3u8"></video></html>',
    'https://site.test/watch/1');
  eq(parsedPage.playlists.map(p => p.url), ['https://site.test/local.m3u8', 'https://cdn.test/movie.m3u8?x=1&y=2'],
    'server M3U extraction finds relative and escaped playlist links');
  eq(netlifyServer._parse.isM3uText('#EXTM3U\n#EXTINF:1,\na.ts'), true, 'Netlify resolver twin contains the same M3U detector');

  const originalFetch = global.fetch;
  try {
    global.fetch = async url => {
      const u = String(url);
      if (u.endsWith('/master.m3u8')) return new Response(master, { status: 200, headers: { 'content-type': 'application/vnd.apple.mpegurl' } });
      if (u.endsWith('/watch')) return new Response('<html><script>var x="https://cdn.test/from-page.m3u8";</script></html>', { status: 200 });
      if (u.endsWith('/not-playlist')) return new Response('<html>no playlist</html>', { status: 200 });
      if (u.endsWith('/huge.m3u8')) return new Response('#EXTM3U\n' + 'x'.repeat(1600000), { status: 200 });
      return new Response('not found', { status: 404 });
    };
    const textOut = await server._find.handle('text', 'https://cdn.test/master.m3u8', {});
    ok(textOut.status === 200 && textOut.body.text.includes('#EXT-X-STREAM-INF'), 'server mode=text returns a validated M3U playlist');
    const m3uOut = await server._find.handle('m3u', 'https://site.test/watch', {});
    ok(m3uOut.status === 200 && m3uOut.body.playlists.some(p => p.url === 'https://cdn.test/from-page.m3u8'),
      'server mode=m3u extracts playlist links from a watch page');
    eq((await server._find.handle('text', 'https://site.test/not-playlist', {})).status, 415,
      'server mode=text rejects a non-M3U response');
    eq((await server._find.handle('text', 'https://cdn.test/huge.m3u8', {})).status, 413,
      'server mode=text refuses a playlist beyond the streamed safety cap');
    eq((await server._find.handle('m3u', 'http://127.0.0.1/admin', {})).status, 400,
      'server M3U modes keep private/loopback URLs blocked');
  } finally {
    global.fetch = originalFetch;
  }

  /* ---------- entry-point and route wiring ---------- */
  const statics = [
    [html.includes('data-ep-play="') && html.includes('data-ep-download="') && html.includes('e.stopPropagation()'),
      'each episode has separate Play and Download buttons'],
    [html.includes('id="btnDownloadTitle"') && html.includes('downloadTitleBtn.onclick'),
      'title/detail download button is wired to the exact selected episode'],
    [html.includes('id="btnPlayerDownload"') && html.includes('openDownloadForContext(playerContext(st)'),
      'player download button is wired'],
    [html.includes('data-server-download="') && html.includes('closest("[data-server-download]")'),
      'Servers modal download button is wired for verified direct options'],
    [html.includes('id="settingsAddWebsite"') && html.includes('id="discoveryPanel"') && html.includes('id="settingsFastStream"'),
      'Settings contains Add Website, continuous adding and FastStream configuration'],
    [html.includes('id="menuQuickSwitches"') && html.includes('function renderMenuQuickSwitches()'),
      'menu box contains quick feature switches'],
    [html.includes('.setting-control input{width:46px;height:24px') && html.includes('.setting-control input:focus-visible+.slider'),
      'feature switches keep a TV-focusable hit area and visible focus ring'],
    [html.includes('route==="settings"') && html.includes('route==="downloads"'),
      'Settings and Downloads routes are wired'],
    [html.includes('String(job.id).replace(/["\\\\]/g,"\\\\$&")'),
      'download-row selector does not depend on CSS.escape on older TV browsers'],
    [html.includes('activeVariant:{url:activeDownloadObjectUrl,quality:job.quality,bytes:blob.size}'),
      'downloaded-file playback uses the local object URL as its active variant'],
  ];
  for (const [cond, label] of statics) ok(cond, label);

  const vercel = JSON.parse(fs.readFileSync(path.join(root, 'vercel.json'), 'utf8'));
  const rewriteSources = (vercel.rewrites || []).map(r => r.source);
  ok(rewriteSources.includes('/settings') && rewriteSources.includes('/downloads'), 'Vercel clean Settings/Downloads rewrites exist');
  const netlify = fs.readFileSync(path.join(root, 'netlify.toml'), 'utf8');
  ok(netlify.includes('from = "/settings"') && netlify.includes('from = "/downloads"'), 'Netlify clean Settings/Downloads rewrites exist');

  console.log(`\n${passed} download/M3U/settings checks passed`);
})().catch(err => { console.error(err); process.exit(1); });
