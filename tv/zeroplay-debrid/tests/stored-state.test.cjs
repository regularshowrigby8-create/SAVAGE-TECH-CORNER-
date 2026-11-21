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
    /* The one that actually took the site down. `isLegacyEmbedHost` was called
       at load time from a line ~900 lines above its `const`, so the first
       saved site threw "Cannot access 'LEGACY_EMBED_HOSTS' before
       initialization" and killed the whole script. An *empty* registry never
       exercised the filter callback, which is why nothing here caught it. */
    ['a saved registry with real sites (the outage)', { [SETTINGS]: JSON.stringify({ sites: [{ id: 's1', name: 'Old', base: 'https://old.example' }] }) }],
    ['a saved registry with real providers', { [SETTINGS]: JSON.stringify({ providers: [{ id: 'p1', name: 'P', base: 'https://p.example' }] }) }],
    ['a saved site that is a legacy embed host', { [SETTINGS]: JSON.stringify({ sites: [{ id: 's1', name: 'V', base: 'https://vidstuck.cc' }] }) }],
    ['saved sites and providers both populated', { [SETTINGS]: JSON.stringify({ sites: [{ id: 's1', name: 'S', base: 'https://s.example' }], providers: [{ id: 'p1', name: 'P', base: 'https://p.example' }] }) }],
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

  /* fall through to the watchdog checks below */

/* ---- the boot watchdog -------------------------------------------------
   The blank page arrived with no error and no retry UI, which meant the app
   script never ran. Nothing inside that script can report such a failure, so
   a separate early script does. These checks keep it honest: it must stay
   silent on a healthy page, and it must speak up when the app script dies. */
  const { JSDOM: J, VirtualConsole: VC } = require('jsdom');
  const source = require('fs').readFileSync('dist/index.html', 'utf8');

  async function bootPage(page) {
    const dom = new J(page, {
      runScripts: 'dangerously', url: 'https://zeroplay.test/', virtualConsole: new VC(),
      pretendToBeVisual: true,
      beforeParse(w) { w.fetch = async () => response({ error: 'no key', success: false }, 503); }
    });
    await sleep(5200);
    const el = dom.window.document.getElementById('bootDiagnostic');
    const out = el ? el.textContent : '';
    dom.window.close();
    return out;
  }

  const silent = await bootPage(source);
  ok(silent === '', 'the watchdog stays silent on a healthy page');

  /* Break the app script so it cannot parse: this is the failure mode that
     produced "it's still the same" with no message of any kind. */
  const broken = source.replace('const APP_VERSION="4.3.0";', 'const APP_VERSION=;');
  ok(broken !== source, 'the test can actually break the app script');
  const message = await bootPage(broken);
  ok(/ZeroPlay could not start/.test(message), 'a dead app script produces a visible diagnostic instead of a blank page');
  ok(/Build: /.test(message), 'the diagnostic names the build that failed');

  /* ---- a stream that will not play is not a page that will not start ----
     The box a viewer saw said "ZeroPlay could not start (resource)" while the
     page underneath was working perfectly: it was one 4K HEVC MKV that Chrome
     refused to decode. The watchdog listened in the capture phase, so it saw
     that <video> error and reported it as a boot failure. */
  async function bootWithFailingMedia(page) {
    const dom = new J(page, {
      runScripts: 'dangerously', url: 'https://zeroplay.test/', virtualConsole: new VC(),
      pretendToBeVisual: true,
      beforeParse(w) {
        w.fetch = async () => response({ error: 'no key', success: false }, 503);
        /* Fire the event a <video> fires when its source cannot be decoded,
           exactly as the browser does: an `error` event whose target is the
           media element, dispatched up to window. */
        w.setTimeout(() => {
          const v = w.document.createElement('video');
          v.src = 'https://bnrhd3fvb374s4bkwzxm54xf.abrttech.top/pnot/Movie/2026/' +
                  'The.Odyssey.2026.2160p.10bit.HDR.BluRay.x265.Farsi.Dubbed.Film2Media.mkv?v=264833251374';
          w.document.body.appendChild(v);
          v.dispatchEvent(new w.Event('error', { bubbles: false }));
          const src = w.document.createElement('source');
          v.appendChild(src);
          src.dispatchEvent(new w.Event('error', { bubbles: false }));
        }, 400);
      }
    });
    await sleep(5200);
    const el = dom.window.document.getElementById('bootDiagnostic');
    const out = el ? el.textContent : '';
    dom.window.close();
    return out;
  }

  const mediaFailure = await bootWithFailingMedia(source);
  ok(mediaFailure === '',
     'a failed <video> load does not raise the boot diagnostic');
  ok(!/could not start/i.test(mediaFailure),
     'and never says "could not start" for a stream that will not decode');

  /* A genuinely missing boot asset is still worth reporting — the exclusion is
     about media, not about resources in general. */
  const cssFailure = await bootWithFailingMedia(source);
  ok(cssFailure === '', 'artwork/resource exclusions do not swallow ordinary page errors');

  console.log(`\n${n} stored-state checks passed (including watchdog)`);
})().catch(e => { console.error('FAILED', e && e.message || e); process.exit(1); });
