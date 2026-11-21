/* The catalogue and the player are one pipeline.

   Two things this pins down, both of which were broken:

   1. The catalogue ladder. A failed TMDB request used to set a sticky
      `demoMode`, so one bad minute of Wi-Fi replaced every shelf with the
      offline fixtures until a reload. The ladder is now TMDB → Cinemeta →
      the last real answer → offline demo, and only the last rung says
      "offline".

   2. Catalogue addons are playable feeds. The Cinemeta addon index is asked
      which catalogue addons exist; the ones that declare a `stream`
      resource are installed, which is what the player queries at Watch time.
      An addon with no stream resource (Cinemeta itself is one) must never be
      installed as a feed.

   Everything runs against a fake network; no request leaves the process. */
const fs=require('fs');
const {JSDOM,VirtualConsole}=require('jsdom');
const html=fs.readFileSync('index.html','utf8');

const CINEMETA_META={id:'tt1375666',type:'movie',name:'Inception',moviedb_id:27205,poster:'https://images.metahub.space/poster/medium/tt1375666/img',background:'https://images.metahub.space/background/medium/tt1375666/img',description:'A thief steals secrets through dream-sharing.',releaseInfo:'2010',imdbRating:'8.4',genres:['Action','Science Fiction']};
const CATALOGUE={metas:[CINEMETA_META,{id:'tt0133093',type:'movie',name:'The Matrix',moviedb_id:603,poster:'https://images.metahub.space/poster/medium/tt0133093/img',background:'https://images.metahub.space/background/medium/tt0133093/img',description:'A hacker learns the truth.',releaseInfo:'1999',imdbRating:'8.7',genres:['Action']}]};
const TMDB_RESULT={page:1,total_pages:1,total_results:1,results:[{id:27205,media_type:'movie',title:'Inception',poster_path:'/x.jpg',backdrop_path:'/y.jpg',vote_average:8.4,release_date:'2010-07-16',overview:'Dreams.'}]};
const STREAM_ADDON_MANIFEST={id:'org.example.tmdbstreams',name:'TMDB Stream Addon',version:'1.0.0',types:['movie','series'],resources:['stream','catalog'],idPrefixes:['tt','tmdb'],catalogs:[{type:'movie',id:'top'}]};

const errors=[];
const vc=new VirtualConsole();
vc.on('jsdomError',e=>{ const m=String(e.detail||e.message); if(!/scrollTo|Not implemented/.test(m)) errors.push(m); });

/* Per-test network switches. */
const net={tmdb:'off',cinemeta:'up',relay:false,requests:[]};
function fakeResponse(body,status){
  status=status||200;
  return {ok:status>=200&&status<300,status,headers:{get(){return null;}},async json(){return body;},async text(){return typeof body==='string'?body:JSON.stringify(body);}};
}
/* The app starts fetching as its inline script runs, so the stub has to exist
   before parse — otherwise the boot shelves are cached from a real request and
   the later assertions read that cache instead of the test network. */
const dom=new JSDOM(html,{runScripts:'dangerously',url:'https://zeroplay.test/',virtualConsole:vc,pretendToBeVisual:true,
  beforeParse(window){ window.fetch=fakeFetch; }});
const w=dom.window, d=w.document;

let n=0; const ok=(c,l)=>{ if(!c){ console.error('FAIL',l); process.exit(1); } n++; console.log('PASS',l); };
const step=ms=>new Promise(r=>setTimeout(r,ms));

async function fakeFetch(url){
  url=String(url); net.requests.push(url);
  if(url.includes('/api/tmdb')) return net.tmdb==='up'?fakeResponse(TMDB_RESULT):fakeResponse({error:'The catalog is temporarily unavailable.',code:'TMDB_NOT_CONFIGURED'},503);
  const mirror=/^https:\/\/(v3-cinemeta\.strem\.io|cinemeta\.strem\.io|cinemeta-catalogs\.strem\.io)\//;
  if(mirror.test(url)&&url.includes('/catalog/')&&url.includes('year=')){
    /* A real Cinemeta shape for a filter with no releases yet: valid JSON,
       zero titles. This is the rail that made the live page say "offline". */
    return fakeResponse({metas:[]});
  }
  if(mirror.test(url)&&url.includes('/catalog/')){
    if(net.cinemeta==='blocked') return fakeResponse({error:'blocked by network'},403);
    return net.cinemeta==='up'?fakeResponse(CATALOGUE):fakeResponse({error:'down'},503);
  }
  if(mirror.test(url)&&url.includes('/meta/')){
    if(net.cinemeta==='blocked') return fakeResponse({error:'blocked by network'},403);
    return net.cinemeta==='up'?fakeResponse({meta:CINEMETA_META}):fakeResponse({error:'down'},503);
  }
  if(url.includes('v3-cinemeta.strem.io/addon_catalog/')) return fakeResponse({addons:[
    {transportUrl:'https://addon.test/manifest.json',manifest:STREAM_ADDON_MANIFEST},
    {transportUrl:'https://meta.test/manifest.json',manifest:{id:'org.cinemeta',name:'Cinemeta',version:'3',resources:['catalog','meta','addon_catalog'],types:['movie','series'],idPrefixes:['tt'],catalogs:[]}}
  ]});
  if(url==='https://addon.test/manifest.json') return fakeResponse(STREAM_ADDON_MANIFEST);
  if(url.includes('addon.test/stream/movie/tt1375666.json')) return fakeResponse({streams:[{url:'https://cdn.test/inception.mp4',name:'ZeroPlay',title:'1080p · 900 MB'}]});
  if(url.includes('/api/extract')){
    /* The relay is server-side: it reaches the mirror the browser cannot. */
    const inner=new URL(url,'https://zeroplay.test/').searchParams.get('url')||'';
    if(!net.relay) return fakeResponse({error:'relay disabled'},502);
    if(inner.includes('/catalog/')) return fakeResponse(CATALOGUE);
    if(inner.includes('/meta/')) return fakeResponse({meta:CINEMETA_META});
    return fakeResponse({ok:true,mode:'json'});
  }
  return fakeResponse({},404);
};

(async()=>{
  await step(600);

  /* ---- 1. the ladder ---- */

  /* TMDB proxy is not configured in this deployment (503) and Cinemeta is up:
     the catalogue must come from Cinemeta and nothing may claim "offline". */
  const live=await w.eval('tmdb("/trending/all/day",{})');
  ok(Array.isArray(live.results)&&live.results.length>0,'a deployment with no TMDB credential still gets a live catalogue');
  ok(w.eval('catalogMode')==='cinemeta','the catalogue chip names the source that actually answered');
  ok(!w.eval('demoMode'),'a keyless catalogue is not "offline"');
  ok(d.getElementById('demoBanner').classList.contains('hidden'),'the offline banner stays hidden while real titles load');

  /* Both upstreams die and the page is reloaded with no network: the
     in-memory Cinemeta cache is gone, so the only thing left is what was
     persisted. That must still be the titles the viewer already saw. */
  net.cinemeta='down';
  w.eval('cinemetaCache.clear(); cinemetaMetaCache.clear(); cinemetaDownUntil=Date.now()+600000; catalogResponseCache.clear(); catalogCacheLoaded=false;');
  const cached=await w.eval('tmdb("/trending/all/day",{})');
  ok(Array.isArray(cached.results)&&cached.results.length>0,'a reload with no network still shows the catalogue that was already loaded');
  ok(w.eval('catalogMode')==='cached','the chip says the answer is the saved catalogue');
  ok(d.getElementById('demoBanner').classList.contains('hidden'),'"cached" is not reported as offline');
  const persisted=w.eval('!!localStorage.getItem("zp.catalogue.cache.v1")');
  ok(persisted,'the catalogue is persisted so a reload can serve it');

  /* Recovery is not sticky: one good answer clears the mode again. */
  net.cinemeta='up';
  w.eval('cinemetaDownUntil=0');
  await w.eval('tmdb("/movie/popular",{page:1})');
  ok(w.eval('catalogMode')==='cinemeta','a later answer returns the catalogue to live');

  /* A path nothing has ever answered, with every upstream down, is the only
     case that may raise the offline banner — and it must. */
  net.cinemeta='down';
  w.eval('cinemetaDownUntil=Date.now()+600000');
  await w.eval('tmdb("/tv/popular",{page:9})');
  ok(w.eval('catalogMode')==='demo','only a request with no live answer and no cache falls to the offline fixtures');
  ok(!d.getElementById('demoBanner').classList.contains('hidden'),'that case does raise the offline banner');
  net.cinemeta='up';
  w.eval('cinemetaDownUntil=0');
  await w.eval('tmdb("/tv/popular",{page:1})');
  ok(w.eval('catalogMode')==='cinemeta'&&d.getElementById('demoBanner').classList.contains('hidden'),'and the banner clears as soon as the catalogue answers again');

  /* ---- 2. catalogue addons become the player's feeds ---- */

  const before=w.eval('(settings.addons||[]).length');
  /* A crawl is a discovery task: the app's own background cycle stops one
     from running while video is open, so the test clears the player first. */
  const crawl=await w.eval('runDiscoveryCycle("test")');
  const catalogueLeg=w.eval('findGlobalStreams?null:null')===null; /* app is loaded */
  ok(!!catalogueLeg,'the app is loaded before the catalogue leg is checked');
  ok(crawl.errors.filter(e=>/^catalogueAddons:/.test(e)).length===0,'the catalogue index is read without errors during a discovery cycle');
  ok(!!crawl.byCrawler.catalogueAddons,'the discovery cycle runs the catalogue leg');
  ok(crawl.byCrawler.catalogueAddons.seen>=2,'it reports how many catalogue addons it looked at');
  const feeds=w.eval('(settings.addons||[]).map(a=>({name:a.name,base:a.base,via:a.installedVia}))');
  const addon=feeds.find(f=>f.name==='TMDB Stream Addon');
  ok(!!addon,'a catalogue addon that declares a stream endpoint is installed as a feed');
  ok(addon.base==='https://addon.test','the feed keeps the addon base, not its manifest URL');
  ok(addon.via==='catalogue','the feed records that the catalogue installed it');
  ok(!feeds.some(f=>/cinemeta/i.test(f.name)),'a catalogue-only addon (no stream resource) is never installed as a feed');
  ok(w.eval('(settings.addons||[]).length')===before+1,'one install for one playable addon, not one per index entry');

  /* The player's own query path, unchanged: the installed feed answers by id. */
  const shelf=await w.eval("collectFeedStreams({type:'movie',imdbId:'tt1375666',tmdbId:27205})");
  ok(shelf.feeds>=1&&shelf.variants.length>0,'the feed the catalogue installed answers the player with a stream');
  ok(/inception\.mp4$/.test(shelf.variants[0].url),'the stream is the direct file the addon returned');

  /* A second pass must not duplicate the roster. */
  await w.eval('runDiscoveryCycle("test-again")');
  ok(w.eval('(settings.addons||[]).filter(a=>a.base==="https://addon.test").length')===1,'re-reading the index does not duplicate the same feed');

  /* --- A browser that cannot reach the catalogue at all. ---
     This is the state a school/ISP/content-filter puts a viewer in: every
     mirror refuses, so a client-only catalogue renders nothing. The relay is
     server-side and must take over, and the shelves must end up populated
     rather than falling to the offline fixtures. */
  net.cinemeta='blocked'; net.relay=true;
  w.eval('catalogResponseCache.clear(); cinemetaCache.clear(); catalogCacheLoaded=false; cinemetaDownUntil=0;');
  /* catalogRespond takes (path, params, fallback) — the fallback is the last
     rung, so it must be supplied or the call itself throws. The path has to
     be one the ladder actually maps (`/movie/popular` → Cinemeta top). */
  const blocked=await w.eval('catalogRespond("/movie/popular",{page:1},()=>demoResponse("/movie/popular",{page:1}))');
  /* The ladder answers in TMDB shape ({page,total_results,results}), so the
     rows live under `results` whether they came from TMDB or Cinemeta. */
  const rows=(blocked&&blocked.results)||[];
  ok(rows.length>0,'with every mirror blocked, the deployment relay still returns real titles');
  ok(rows.some(m=>(m.title||m.name)==='Inception'),'the titles are the real catalogue titles, not offline demo fixtures');
  ok(net.requests.some(u=>u.startsWith('/api/extract?mode=json&url=')&&decodeURIComponent(u).includes('/catalog/')),'the blocked browser asks this deployment relay instead of giving up');

  /* The relay is a documented capability, not an accidental fallback: it must
     never be handed a non-catalogue URL by this path. */
  const relayed=net.requests.filter(u=>u.startsWith('/api/extract?mode=json&url='));
  const catalogueHosts=/cinemeta\.strem\.io|stremio-addons\.net/;
  ok(relayed.every(u=>catalogueHosts.test(decodeURIComponent(u))),'the relay only ever receives catalogue URLs');

  /* The second shelf must go straight to the relay: three doomed direct
     attempts per rail is what makes a filtered TV feel frozen. */
  const before2=net.requests.length;
  /* A fresh path: the previous shelf is still in cinemetaCache, and a cache
     hit would test nothing. */
  await w.eval('cinemetaFetch(cinemetaWithBase("https://v3-cinemeta.strem.io/catalog/series/top/skip=0.json",0))');
  const second=net.requests.slice(before2);
  ok(second.length===1&&second[0].startsWith('/api/extract?mode=json&url='),'once the relay works it is preferred, so later shelves skip the blocked mirrors');
  ok(w.eval('Date.now() < cinemetaRelayUntil'),'the relay preference is on a timer, so a fixed network does not stay pinned to it forever');

  net.cinemeta='up'; net.relay=false; w.eval('cinemetaRelayUntil=0; cinemetaCache.clear();');

  /* --- One empty rail must not speak for the whole page. ---
     The live build showed "You are offline" above a full grid of posters
     because a single shelf (a year filter with no releases yet) came back
     empty and toggled a page-wide banner. */
  w.eval('catalogReportReset(); setDemo(false); catalogMode="tmdb";');
  await w.eval('tmdb("/movie/popular",{page:1})');
  ok(!w.eval('demoMode'),'a page with a live shelf is not offline');
  await w.eval('tmdb("/movie/now_playing",{page:1})');
  const bannerVisible=!d.getElementById('demoBanner').classList.contains('hidden');
  ok(!bannerVisible,'an empty shelf on a page that has live titles raises no offline banner');
  ok(w.eval('demoMode')===false,'one empty shelf does not set the sticky offline mode');

  /* And the now-playing rail widened its year instead of handing back zero. */
  const nowPlaying=await w.eval('tmdb("/movie/now_playing",{page:1})');
  ok((nowPlaying.results||[]).length>0,'the now-playing rail widens the year instead of coming back empty');

  /* A genuinely dead page still says so, once. */
  w.eval('catalogReportReset(); setDemo(false);'); net.cinemeta='down';
  await w.eval('cinemetaDownUntil=Date.now()+600000; catalogResponseCache.clear(); tmdb("/tv/on_the_air",{page:7})');
  ok(w.eval('demoMode'),'a page where nothing answers still says it is offline');
  net.cinemeta='up'; w.eval('cinemetaDownUntil=0');

  ok(errors.length===0,'the app runs the whole pipeline with no runtime error');
  console.log(`\n${n} catalogue/player checks passed`);
})().catch(e=>{console.error('FAILED',e&&e.message||e);process.exit(1);});
