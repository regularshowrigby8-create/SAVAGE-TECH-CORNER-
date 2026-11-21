/* Overlay audit: the parts a viewer feels but a screenshot does not show —
   what is stacked on what, what Back closes next, and who owns the page. */
const fs=require('fs'), path=require('path'), vm=require('vm');
const {JSDOM, VirtualConsole}=require('jsdom');
const html=fs.readFileSync(path.join(__dirname,'..','index.html'),'utf8');
const { appScript } = require('./app-script.cjs');
const src=appScript(html);
let n=0;
const ok=(cond,label)=>{ if(!cond){ console.error('FAIL',label); process.exit(1); } n++; console.log('PASS',label); };
const has=(needle,label)=>ok(src.includes(needle),label);
const lacks=(needle,label)=>ok(!src.includes(needle),label);

console.log('--- scroll lock is one authority ---');
ok((src.match(/classList\.add\("locked"\)/g)||[]).length===0,
   'no overlay adds "locked" by hand any more');
ok((src.match(/classList\.remove\("locked"\)/g)||[]).length===0,
   'no overlay removes "locked" by hand any more');
has('function bodyScrollIsLocked(){','the lock is derived from what is actually open');
has('document.querySelector("#playerRoot .player-overlay")','the player counts as open');
has('document.querySelector("#downloadsRoot .overlay")','the download sheet counts as open');
has('function syncBodyScrollLock(){ document.body.classList.toggle("locked",bodyScrollIsLocked()); }',
    'one setter writes the class');

console.log('\n--- stacking order is deliberate ---');
const zIndex=(sel)=>{
  const m=new RegExp(sel.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')+'\\s*\\{[^}]*z-index:\\s*(\\d+)').exec(html);
  return m?Number(m[1]):null;
};
const zDetail=zIndex('\n.overlay'), zPlayer=zIndex('.player-overlay'),
      zStreams=zIndex('#streamsRoot .overlay'), zDownloads=zIndex('#downloadsRoot .overlay'),
      zToasts=zIndex('#toasts'), zBar=zIndex('.topbar'), zBanner=zIndex('.demo-banner');
ok(zDetail<zPlayer, `detail (${zDetail}) sits under the player (${zPlayer})`);
ok(zPlayer<zStreams, `player (${zPlayer}) sits under the servers sheet (${zStreams})`);
ok(zStreams<zDownloads, `servers (${zStreams}) sits under download (${zDownloads})`);
ok(zToasts>zDownloads, `toasts (${zToasts}) stay above every overlay`);
ok(zBanner<zBar, `the offline banner (${zBanner}) cannot cover the sticky topbar (${zBar})`);

console.log('\n--- you cannot open what you cannot see ---');
has('if(playerState) closePlayer();\n  if(typeof rememberTvFocus==="function")',
    'opening a detail sheet inside playback closes the player first');
has('if(bodyScrollIsLocked()&&!document.getElementById("menuDrawer")?.classList.contains("open"))',
    'the TV menu key will not open a drawer under a sheet');
has('const sheetOpen=document.querySelector("#modalRoot .overlay,#streamsRoot .overlay,#downloadsRoot .overlay");',
   'that guard looks for a sheet that is actually up');

console.log('\n--- playback behind a sheet ---');
has('st.sheetPaused=true; v.pause();','opening the servers sheet pauses the film behind it');
has('if(playerState&&playerState.sheetPaused){','closing it resumes only if we paused');
has('if(btn&&btn.isConnected) btn.focus({preventScroll:true}); },20);',
    'closing download hands focus back to the player');

console.log('\n--- Back closes what the viewer is looking at ---');
/* Back must peel the stack from the top: drawer, then the sheets that sit
   above the player, then the player itself, then the detail sheet underneath. */
const marks={
  drawer:'const drawer=document.getElementById("menuDrawer");',
  download:'if(typeof downloadModalState!=="undefined"&&downloadModalState)',
  streams:'if(streamsCtx){ closeStreams();',
  more:'const moreSheet=document.getElementById("playerMoreSheet");',
  next:'if(playerState&&playerState.nextPromptOpen)',
  tracks:'const tracksSheet=document.getElementById("playerTracksSheet");',
  player:'if(playerState){\n    if(document.fullscreenElement)',
  detail:'if(detailState){ closeDetail();'
};
const base=src.indexOf('function handleZeroPlayTvBack()');
const pos=Object.entries(marks).map(([k,v])=>[k,src.indexOf(v,base)]);
ok(pos.every(([,p])=>p>base), 'every Back branch is present');
ok(pos.every(([,p],i)=>i===0||p>pos[i-1][1]),
   'Back closes innermost first: '+pos.map(([k])=>k).join(' → '));

console.log('\n--- no second lock authority left behind ---');
lacks('if(!playerState&&!detailState&&!streamsCtx) document.body.classList','the old three-way guard is gone');
lacks('if(!playerState&&!detailState) document.body.classList','the old two-way guard is gone');

console.log('\n--- dead parts ---');
['directLinkFeed','feedIdForBase'].forEach(name=>{
  const uses=(src.match(new RegExp('\\b'+name+'\\b','g'))||[]).length;
  ok(uses===1, `${name} is defined once and never called — recorded as dead, not wired by accident`);
});
const tomb=(src.match(/rememberDeletedDownloadId/g)||[]).length;
ok(tomb===2,'deleted-download tombstones are added through the bounded helper, not the raw set');

console.log(`\n${n} overlay checks passed`);
