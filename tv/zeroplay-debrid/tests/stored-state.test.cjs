/* Saved state outlives the build that wrote it.

   This suite exists because of a real blank page: a viewer who had used a
   previous version of ZeroPlay had a `zp_rd_watchlist_v1` of an older shape.
   The shell painted, `renderHome` threw on the first `.map`, and #view stayed
   empty under a working header and a working sidebar — the app looked dead
   while every test here passed, because the tests all started from an empty
   localStorage.

   So each case below boots the real built page with deliberately wrong saved
   data and requires the catalogue to still appear. The last case is the
   safety net: an entry no normaliser can rescue must produce a retry UI, not
   a blank screen. */
const fs = require('fs');
const { JSDOM, VirtualConsole } = require('jsdom');

const html = fs.readFileSync('dist/index.html', 'utf8');
let n = 0;
const ok = (c, l) => { if (!c) { console.error('FAIL', l); process.exit(1); } n++; console.log('PASS', l); };
const sleep = ms => new Promise(r => setTimeout(r, ms));

function response(body, status) {
  status = status || 200;
  return { ok: status >= 200 && status < 300, status, headers: { get() { return null; } },
    async json() { return body; }, async text() { return typeof body === 'string' ? body : JSON.stringify(body); } };
}
function fakeFetch(url) {
  url = String(url);
  if (url.includes('/api/tmdb')) return response({ error: 'no key', success: false }, 503);
  if (url.includes('/catalog/')) return response({ metas: [1, 2, 3, 4, 5].map(i => ({
    id: 'tt000000' + i, type: 'movie', name: 'Title ' + i, moviedb_id: i,
    poster: 'https://images.metahub.space/poster/medium/tt000000' + i + '/img', releaseInfo: '2024' })) });
  if (url.includes('/meta/')) return response({ meta: { id: 'tt0000001', name: 'Title 1', moviedb_id: 1 } });
  if (url.includes('/api/extract')) return response({ ok: true });
  return response({}, 404);
}

async function boot(seed) {
  const errors = [];
  const vc = new VirtualConsole();
  vc.on('jsdomError', e => {
    const m = String(e.detail || e.message);
    if (!/scrollTo|Not implemented/.test(m)) errors.push(m);
  });
  const dom = new JSDOM(html, {
    runScripts: 'dangerously', url: 'https://zeroplay.test/', virtualConsole: vc,
    pretendToBeVisual: true,
    beforeParse(window) {
      for (const [k, v] of Object.entries(seed)) window.localStorage.setItem(k, v);
      window.fetch = fakeFetch;
    }
  });
  await sleep(2500);
  const d = dom.window.document;
  const view = d.getElementById('view');
  const result = {
    errors,
    posters: view ? view.querySelectorAll('img[src*=metahub]').length : 0,
    retry: !!d.getElementById('routeRetryBtn'),
    text: (view ? view.textContent : '').replace(/\s+/g, ' ').trim(),
  };
  dom.window.close();
  return result;
}

(async () => {
  const SETTINGS = 'zp_rd_settings_v1';
  const cases = [
    ['a watchlist saved as an object', { ['zp_rd_watchlist_v1']: '{"a":1}', [SETTINGS]: '{}' }],
    ['a watchlist saved as a string', { ['zp_rd_watchlist_v1']: '"nope"', [SETTINGS]: '{}' }],
    ['a watchlist saved as a number', { ['zp_rd_watchlist_v1']: '42', [SETTINGS]: '{}' }],
    ['a watchlist containing empty entries', { ['zp_rd_watchlist_v1']: '[null,null,{"id":7,"type":"movie","title":"Real"}]', [SETTINGS]: '{}' }],
    ['progress saved as a string', { ['zp_rd_progress_v1']: '"x"', [SETTINGS]: '{}' }],
    ['settings saved as a string', { [SETTINGS]: '"hello"' }],
    ['settings saved as an array', { [SETTINGS]: '[1,2,3]' }],
    ['settings.sites saved as an object', { [SETTINGS]: '{"sites":{"a":1}}' }],
    ['settings.providers saved as a string', { [SETTINGS]: '{"providers":"nope"}' }],
    ['settings.stream saved as a string', { [SETTINGS]: '{"stream":"fast"}' }],
    ['settings.discovery saved as a string', { [SETTINGS]: '{"discovery":"on"}' }],
    ['every key poisoned at once', { ['zp_rd_watchlist_v1']: '{"x":1}', ['zp_rd_progress_v1']: '"p"', [SETTINGS]: '"s"' }],
  ];

  for (const [label, seed] of cases) {
    const r = await boot(seed);
    ok(r.errors.length === 0, `no runtime error: ${label}`);
    ok(r.posters > 0, `the catalogue still renders: ${label}`);
  }

  /* A legacy-but-valid library is data, not damage: it must survive. */
  const legacy = await boot({ ['zp_rd_watchlist_v1']: JSON.stringify([{ id: 1, type: 'movie', title: 'Old', year: 1999 }]), [SETTINGS]: '{}' });
  ok(legacy.posters > 0, 'a library written by an older build does not stop the catalogue');
  ok(/My Watchlist/.test(legacy.text), 'and that library is still shown to the viewer');

  /* The net. Nothing here can be normalised, so the render itself fails; the
     point is that the viewer gets a way out instead of an empty page. */
  const net = await boot({ ['zp_rd_watchlist_v1']: JSON.stringify([{ id: null, type: 'movie' }]), [SETTINGS]: '{}' });
  ok(net.text.length > 0, 'a failure that cannot be normalised never leaves an empty page');
  ok(net.retry || net.posters > 0, 'and it offers a retry, or renders anyway');

  console.log(`\n${n} stored-state checks passed`);
})().catch(e => { console.error('FAILED', e && e.message || e); process.exit(1); });
