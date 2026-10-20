const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..');
const read=file=>fs.readFileSync(path.join(root,file),'utf8');

test('website enhancement bundle is wired into the web shell',()=>{
  const html=read('web/public/index.html');
  assert.match(html,/manifest\.json/);
  assert.match(html,/enhancements\.css/);
  assert.match(html,/web-enhancements\.js/);
  assert.match(html,/web-polish-fixes\.js/);
  assert.ok(fs.existsSync(path.join(root,'web/public/sw.js')));
});

test('website discovery includes filters search upgrades and personalized home rows',()=>{
  const js=read('web/public/web-enhancements.js');
  assert.match(js,/data-web-filter/);
  assert.match(js,/\/discover\//);
  assert.match(js,/web-search-suggestions/);
  assert.match(js,/Trending searches/);
  assert.match(js,/Because you watched/);
  assert.match(js,/Popular anime/);
  assert.match(js,/Homepage sections/);
});

test('website supports richer details franchises routes keyboard and install flow',()=>{
  const js=read('web/public/web-enhancements.js');
  assert.match(js,/belongs_to_collection/);
  assert.match(js,/web-detail-meta/);
  assert.match(js,/zeroPlayScroll/);
  assert.match(js,/\/movie\//);
  assert.match(js,/beforeinstallprompt/);
  assert.match(js,/ArrowRight/);
  assert.match(js,/event\.key==='\/'/);
});

test('website preserves loaded pages on detail back and syncs provider filter mode',()=>{
  const polish=read('web/public/web-polish-fixes.js');
  assert.match(polish,/historyRestoreUntil/);
  assert.match(polish,/document\.documentElement\.dataset\.section===section/);
  assert.match(polish,/data-provider-type/);
  assert.match(polish,/\/genre\/'\+providerType/);
});

test('website infinite scrolling deduplicates pages retries and waits until the real bottom',()=>{
  const infinite=read('web/public/infinite-scroll.js');
  assert.match(infinite,/<500/);
  assert.match(infinite,/window\.scrollY>0/);
  assert.match(infinite,/existing\.has/);
  assert.match(infinite,/retries<=2/);
  assert.match(infinite,/Loading more titles/);
});

test('deep website routes rewrite to the SPA on Vercel and Netlify',()=>{
  const vercel=JSON.parse(read('vercel.json'));
  const sources=new Set((vercel.rewrites||[]).map(row=>row.source));
  for(const route of ['/movie/:id','/tv/:id','/movies','/series','/library','/search'])assert.ok(sources.has(route));
  const netlify=read('netlify.toml');
  for(const route of ['/movie/*','/tv/*','/movies','/series','/library','/search'])assert.match(netlify,new RegExp('from = "'+route.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')+'"'));
});

test('PWA manifest is standalone and remains movie-series only',()=>{
  const manifest=JSON.parse(read('web/public/manifest.json'));
  assert.equal(manifest.display,'standalone');
  assert.equal(manifest.short_name,'ZeroPlay');
  assert.doesNotMatch(JSON.stringify(manifest),/Live TV|Live Sports|PPV/i);
});
