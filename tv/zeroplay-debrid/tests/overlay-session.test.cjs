const fs=require('fs'), path=require('path');
const {JSDOM,VirtualConsole}=require('jsdom');
const html=fs.readFileSync(require('path').join(__dirname,'..','index.html'),'utf8');
const errs=[];
const vc=new VirtualConsole();
vc.on('jsdomError',e=>{const m=String(e.detail||e.message); if(!/scrollTo|Not implemented/.test(m)) errs.push(m);});
const dom=new JSDOM(html,{runScripts:'dangerously',url:'https://zp.test/',virtualConsole:vc,pretendToBeVisual:true});
const w=dom.window,d=w.document;
w.fetch=async u=>{
  u=String(u);
  if(u.includes('/manifest.json')) return {ok:true,json:async()=>({id:'t',name:'Test Feed',version:'1',types:['movie','series'],resources:['stream'],idPrefixes:['tt']})};
  if(u.includes('/stream/')) return {ok:true,json:async()=>({streams:[{url:'https://cdn.test/v.mp4',name:'ZeroPlay',title:'1080p'}]})};
  if(u.includes('/movie/')||u.includes('/tv/')) return {ok:true,json:async()=>({id:550,title:'Fight Club',name:'Fight Club',overview:'x',genres:[],credits:{cast:[]},videos:{results:[]},recommendations:{results:[]},seasons:[],external_ids:{imdb_id:'tt0137523'}})};
  return {ok:true,json:async()=>({results:[]}),text:async()=>''};
};
const wait=ms=>new Promise(r=>setTimeout(r,ms));
let n=0; const ok=(c,l)=>{ if(!c){console.error('FAIL',l); process.exit(1);} else {n++; console.log('PASS',l);} };
const locked=()=>d.body.classList.contains('locked');
const openCount=()=>d.querySelectorAll('#playerRoot .player-overlay,#modalRoot .overlay,#streamsRoot .overlay,#downloadsRoot .overlay').length;
(async()=>{
  await wait(400);
  ok(!locked(),'fresh page scrolls');

  // 1. detail sheet: opens, locks, closes, unlocks
  w.eval("openDetail('movie',550)");
  await wait(300);
  ok(openCount()===1 && locked(),'detail sheet opens and locks the page');
  w.eval('closeDetail()');
  await wait(100);
  ok(openCount()===0 && !locked(),'closing detail releases the lock');

  // 2. player alone
  w.eval("openPlayer({id:550,title:'Fight Club',type:'movie',imdbId:'tt0137523'}, {})");
  await wait(600);
  const playerOpen=!!d.querySelector('#playerOverlay');
  ok(playerOpen&&locked(),'player opens and locks the page');

  // 3. detail opened while the player is up must not hide under it
  w.eval("openDetail('movie',550)");
  await wait(400);
  ok(!d.querySelector('#playerOverlay'),'detail from inside playback does not leave the player underneath');
  ok(openCount()===1,'only one overlay is present, not two stacked');

  // close that detail -> page must unlock (the bug we fixed)
  w.eval('closeDetail()');
  await wait(120);
  ok(openCount()===0&&!locked(),'no phantom scroll lock after the detail closes');

  // 4. servers sheet from player pauses and resumes
  w.eval("openPlayer({id:550,title:'Fight Club',type:'movie',imdbId:'tt0137523'}, {})");
  await wait(500);
  w.eval("document.getElementById('btnPlayerServers').click()");
  await wait(400);
  const sheetUp=!!d.querySelector('#streamsOverlay');
  ok(sheetUp,'servers sheet opens over the player');
  ok(locked(),'page stays locked with the sheet up');
  w.eval('closeStreams()');
  await wait(200);
  ok(!d.querySelector('#streamsOverlay')&&!!d.querySelector('#playerOverlay'),'closing the sheet returns to the player, which is still open');
  ok(locked(),'player keeps the lock after the sheet closes');

  w.eval('closePlayer()');
  await wait(400);
  ok(openCount()===0&&!locked(),'closing the player last releases the lock');

  ok(errs.length===0,'the overlay session raises no runtime error');
  console.log(`\n${n} overlay session checks passed`);
})();
