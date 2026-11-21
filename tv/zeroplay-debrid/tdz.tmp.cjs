const {JSDOM,VirtualConsole}=require('jsdom');
const fs=require('fs');
const html=fs.readFileSync('dist/index.html','utf8');
const R=(b,st=200)=>({ok:st<300,status:st,headers:{get(){return null;}},json:async()=>b,text:async()=>JSON.stringify(b)});
const fetchImpl=(u)=>{u=String(u);
  if(u.includes('/api/tmdb'))return R({error:'no key',success:false},503);
  if(u.includes('/catalog/'))return R({metas:[{id:'tt1',type:'movie',name:'A',moviedb_id:1,poster:'https://images.metahub.space/poster/medium/tt1/img'}]});
  if(u.includes('/meta/'))return R({meta:{id:'tt1',name:'A',moviedb_id:1}});
  if(u.includes('/api/extract'))return R({ok:true});
  return R({},404);};
async function run(label,seed){
  const dom=new JSDOM(html,{runScripts:'dangerously',url:'https://zeroplay.test/',virtualConsole:new VirtualConsole(),pretendToBeVisual:true,
    beforeParse(w){for(const[k,v] of Object.entries(seed)) w.localStorage.setItem(k,v); w.fetch=fetchImpl;}});
  await new Promise(r=>setTimeout(r,3500));
  const d=dom.window.document,b=d.getElementById('bootDiagnostic'),v=d.getElementById('view');
  console.log((b?'FAIL':'PASS'), label.padEnd(46), 'posters='+(v?v.querySelectorAll('img[src*=metahub]').length:0), b?('::'+b.textContent.split('\n')[2]):'');
  dom.window.close();
  return !b;
}
(async()=>{
  let ok=true;
  // THE case that killed the browser: a saved registry with real entries.
  ok=await run('saved sites array (the real crash)', {zp_rd_settings_v1:JSON.stringify({sites:[{id:'s1',name:'Old',base:'https://old.example'}]})})&&ok;
  ok=await run('saved providers array', {zp_rd_settings_v1:JSON.stringify({providers:[{id:'p1',name:'P',base:'https://p.example'}]})})&&ok;
  ok=await run('saved site that IS a legacy embed host', {zp_rd_settings_v1:JSON.stringify({sites:[{id:'s1',name:'V',base:'https://vidstuck.cc'}]})})&&ok;
  ok=await run('empty saved settings', {zp_rd_settings_v1:'{}'})&&ok;
  ok=await run('no saved state at all', {})&&ok;
  process.exit(ok?0:1);
})();
