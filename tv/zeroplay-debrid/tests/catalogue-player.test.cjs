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
const net={tmdb:'off',cinemeta:'up',requests:[]};
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
  if(url.includes('v3-cinemeta.strem.io/catalog/')) return net.cinemeta==='up'?fakeResponse(CATALOGUE):fakeResponse({error:'down'},503);
  if(url.includes('v3-cinemeta.strem.io/meta/')) return net.cinemeta==='up'?fakeResponse({meta:CINEMETA_META}):fakeResponse({error:'down'},503);
  if(url.includes('v3-cinemeta.strem.io/addon_catalog/')) return fakeResponse({addons:[
    {transportUrl:'https://addon.test/manifest.json',manifest:STREAM_ADDON_MANIFEST},
    {transportUrl:'https://meta.test/manifest.json',manifest:{id:'org.cinemeta',name:'Cinemeta',version:'3',resources:['catalog','meta','addon_catalog'],types:['movie','series'],idPrefixes:['tt'],catalogs:[]}}
  ]});
  if(url==='https://addon.test/manifest.json') return fakeResponse(STREAM_ADDON_MANIFEST);
  if(url.includes('addon.test/stream/movie/tt1375666.json')) return fakeResponse({streams:[{url:'https://cdn.test/inception.mp4',name:'ZeroPlay',title:'1080p · 900 MB'}]});
  if(url.includes('/api/extract')) return fakeResponse({ok:true,mode:'json'});
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

  ok(errors.length===0,'the app runs the whole pipeline with no runtime error');
  console.log(`\n${n} catalogue/player checks passed`);
})().catch(e=>{console.error('FAILED',e&&e.message||e);process.exit(1);});
