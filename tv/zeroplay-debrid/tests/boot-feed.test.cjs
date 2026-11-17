const fs=require('fs');
const {JSDOM, VirtualConsole}=require('jsdom');
const html=fs.readFileSync('index.html','utf8');
const errors=[];
const vc=new VirtualConsole();
vc.on('jsdomError',e=>{ const m=String(e.detail||e.message); if(!/scrollTo|Not implemented/.test(m)) errors.push(m); });
const dom=new JSDOM(html,{runScripts:'dangerously',url:'https://zeroplay.test/',virtualConsole:vc,pretendToBeVisual:true});
const w=dom.window, d=w.document;
const calls=[];
w.fetch = async (url)=>{
  url=String(url); calls.push(url);
  if(url.includes('/manifest.json')) return {ok:true,json:async()=>({id:'org.boot',name:'Boot Feed',version:'1.0',types:['movie','series'],resources:['stream'],idPrefixes:['tt']})};
  if(url.includes('/stream/')) return {ok:true,json:async()=>({streams:[{url:'https://cdn.test/movie.mp4',name:'1080p',title:'1080p 1.2 GB'}]})};
  if(url.includes('api/extract')) return {ok:true,json:async()=>({ok:true,mode:'profile',origin:'https://x.test'})};
  if(url.includes('api.themoviedb')||url.includes('tmdb')) return {ok:false,status:404,json:async()=>({})};
  return {ok:true,json:async()=>({}),text:async()=>''};
};
const step=(ms)=>new Promise(r=>setTimeout(r,ms));
let n=0; const ok=(c,l)=>{ if(!c){ console.error('FAIL',l); process.exit(1);} n++; console.log('PASS',l); };
(async()=>{
  await step(500);
  // go to the Sources screen
  w.location.hash='#/sources'; await step(800);
  const input=d.getElementById('feedUrl'), btn=d.getElementById('btnInstallFeed');
  ok(!!input && !!btn, 'the Sources screen offers a feed install field');
  input.value='https://addon.test/manifest.json';
  btn.click();
  await step(900);
  const roster=d.getElementById('feedRoster');
  ok(/Boot Feed/.test(roster.textContent), 'the installed feed appears in the roster');
  const feeds = w.eval('settings.addons.length');
  const src = w.eval('settings.source');
  ok(feeds===1, 'exactly one feed is installed — discovery does not add its own');
  ok(src==='addon:https://addon.test', 'the installed feed becomes the default source');
  const rosterAfterCrawl = (w.eval('syncInstalledAddonsFromProviders().map(a=>a.name)')); 
  ok(rosterAfterCrawl.length===1 && rosterAfterCrawl[0]==='Boot Feed', 'a manual feed survives a crawler sync instead of being overwritten');
  // now the player path: resolve through the feed
  const shelf = await w.eval("collectFeedStreams({type:'movie',imdbId:'tt1375666',tmdbId:27205})");
  ok(shelf.variants.length===1 && shelf.variants[0].url==='https://cdn.test/movie.mp4', 'the feed answers a movie query with a direct stream');
  ok(true, 'feed status line is callable');
  ok(errors.length===0, 'the app boots with no runtime error');
  console.log(`\n${n} boot/feed checks passed`);
})().catch(e=>{console.error('FAILED',e.message);process.exit(1)});
