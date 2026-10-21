const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..');
const read=file=>fs.readFileSync(path.join(root,file),'utf8');

test('website is labeled as ZeroPlay 2.2.2 Web',()=>{
  const html=read('web/public/index.html');
  const browser=read('web/public/browser.js');
  assert.match(html,/ZeroPlay 2\.2/);
  assert.match(browser,/version:'2\.2\.2 Web'/);
});

test('website does not expose Live TV, PPV or Live Sports',()=>{
  const html=read('web/public/index.html');
  assert.doesNotMatch(html,/data-live-nav|live-player|web-live\.js|live\.css|shaka-player/i);
  assert.equal(fs.existsSync(path.join(root,'web/public/web-live.js')),false);
  assert.equal(fs.existsSync(path.join(root,'web/public/live.css')),false);
  assert.equal(fs.existsSync(path.join(root,'api/live.js')),false);
});

test('website movies and series append more cards below the current grid',()=>{
  const html=read('web/public/index.html');
  const infinite=read('web/public/infinite-scroll.js');
  const sw=read('web/public/sw.js');
  assert.match(html,/infinite-scroll\.js\?v=20261013c/);
  assert.match(sw,/zeroplay-web-2\.2\.2/);
  assert.match(sw,/infinite-scroll\.js\?v=20261013c/);
  assert.match(infinite,/category==='Movies'\|\|category==='Series'/);
  assert.match(infinite,/async function appendCatalogPage/);
  assert.match(infinite,/zero22CatalogRequest\?window\.zero22CatalogRequest\(section,nextPage\):null/);
  assert.match(infinite,/requestParams=request\?\.params\|\|\{page:nextPage\}/);
  assert.match(infinite,/const data=await api\(path,requestParams\)/);
  assert.match(infinite,/grid\.insertAdjacentHTML\('beforeend',html\)/);
  assert.match(infinite,/page=nextPage/);
  assert.match(infinite,/grid\.children\.length\+' titles loaded'/);
  assert.match(infinite,/if\(canAppendCatalog\(\)\)\{appendCatalogPage\(grid,pager\);return;\}/);
  assert.match(infinite,/function nearBottom\(\)\{return window\.scrollY>0/);
  assert.match(infinite,/function resetTabPosition\(\)/);
  assert.match(infinite,/if\(y>lastScrollY\+2\)\{armScrollIntent\(\);scheduleCheck\(\);\}/);
});

test('shared movie and TV details stay closed after dismissal',()=>{
  const html=read('web/public/index.html');
  const router=read('web/public/web-router.js');
  const enhancements=read('web/public/web-enhancements.js');
  const css=read('web/public/zsflix-ui.css');
  for(const asset of ['/style.css','/layouts.css','/app.js','/web-router.js','/zsflix-ui.css'])assert.match(html,new RegExp(asset.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')));
  assert.match(html,/web-router\.js\?v=20261014d/);
  assert.match(enhancements,/const initialPath=location\.pathname/);
  assert.match(enhancements,/applyRoute\(true\)/);
  assert.match(router,/navigator\.share/);
  assert.match(router,/ZeroPlay link copied/);
  assert.match(router,/function clearDetailRoute\(\)/);
  assert.match(router,/function suppressReopen\(\)/);
  assert.match(router,/suppressUntil=Date\.now\(\)\+1400/);
  assert.match(router,/if\(ref&&ref===suppressedRef&&Date\.now\(\)<suppressUntil\)return/);
  assert.match(router,/history\.replaceState/);
  assert.match(router,/detail\?\.addEventListener\('close',closeDetailsAndRoute\)/);
  assert.match(router,/window\.addEventListener\('popstate'/);
  assert.match(css,/--zp-mint:#66f2c3/);
});

test('website header is collapsed to one bar',()=>{
  const html=read('web/public/index.html');
  const css=read('web/public/singlebar.css');
  assert.match(html,/singlebar\.css\?v=20261014d/);
  assert.match(css,/header\{[\s\S]*position:fixed!important;[\s\S]*top:0!important/);
  assert.match(css,/main\{padding-top:72px!important\}/);
  assert.match(css,/aside \[data-action="settings"\]\{display:none!important\}/);
  assert.match(css,/\.tools\{[\s\S]*position:absolute!important/);
});

test('homepage hero loads trailer previews and official title artwork',()=>{
  const html=read('web/public/index.html');
  const trailer=read('web/public/hero-trailer.js');
  const css=read('web/public/singlebar.css');
  const catalog=require(path.join(root,'web/lib/catalog.cjs'));
  const vercel=read('vercel.json');
  assert.match(html,/hero-trailer\.js\?v=20261014d/);
  assert.match(trailer,/youtube\.com\/embed/);
  assert.match(trailer,/autoplay=1&mute=1/);
  assert.match(trailer,/append_to_response:'videos,images'/);
  assert.match(trailer,/include_image_language:'en,null'/);
  assert.match(trailer,/hero-title-logo/);
  assert.match(trailer,/audio\.dataset\.heroAudio='1'/);
  assert.match(trailer,/TRAILER_MS=28000/);
  assert.match(css,/\.hero-title-logo/);
  assert.match(css,/\.hero\.has-title-logo \.hero-content h2/);
  const params=catalog.query({path:'/movie/460458',append_to_response:'videos,images',include_image_language:'en,null'});
  assert.equal(params.get('append_to_response'),'videos,images');
  assert.equal(params.get('include_image_language'),'en,null');
  assert.match(vercel,/youtube\.com/);
});
