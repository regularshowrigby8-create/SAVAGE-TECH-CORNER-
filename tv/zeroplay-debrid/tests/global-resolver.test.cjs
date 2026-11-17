/* Global resolver + player integration.
   Covers the 14 spec modules, the player surface, and the join between them —
   the join is where every real defect was found, so it gets the most attention. */
const fs=require("fs"), path=require("path");
const ROOT=path.join(__dirname,"..");
let n=0, failed=0;
const ok=(c,l)=>{ if(c){n++;console.log("PASS",l);} else {failed++;console.error("FAIL",l);} };
const req=p=>require(path.join(ROOT,p));

console.log("--- every spec module loads ---");
const modules={
  "lib/global-resolver/policy":"policy",
  "lib/global-resolver/frontier":"frontier",
  "lib/global-resolver/identity":"identity",
  "lib/global-resolver/adapters":"adapters",
  "lib/global-resolver/resolver":"resolver",
  "lib/global-resolver/hardening":"hardening",
  "lib/global-search":"global-search",
  "lib/global-results-ui":"global-results-ui",
  "lib/global-source-onboarding":"global-source-onboarding",
  "lib/global-source-manager-ui":"global-source-manager-ui",
  "api/global-resolve":"api/global-resolve",
  "lib/global-client":"global-client",
  "lib/global-diagnostics-ui":"global-diagnostics-ui",
  "lib/global-bootstrap":"global-bootstrap"
};
const loaded={};
for(const [file,name] of Object.entries(modules)){
  try{ loaded[name]=req(file); ok(true,name+" loads"); }
  catch(e){ ok(false,name+" loads ("+e.message+")"); }
}

console.log("\n--- the resolver exposes what it advertises ---");
const {createGlobalResolver}=loaded.resolver;
const resolver=createGlobalResolver({});
ok(typeof resolver.getStatistics==="function","getStatistics exists (it was exported but undefined)");
const stats=resolver.getStatistics();
ok(stats&&stats.sources&&typeof stats.sources.totalSources==="number","statistics reports a source count");
ok(stats.policy&&typeof stats.policy.globalConcurrency==="number","statistics reports live policy limits");

console.log("\n--- policy: the guard the batches specify ---");
const policy=loaded.policy;
const guard=(u,exp)=>ok(!!policy.guardUrl(u)===exp,`${exp?"allows":"blocks"} ${u}`);
guard("https://example.com/film.mp4",true);
guard("https://cdn.example.com/live.m3u8",true);
guard("magnet:?xt=urn:btih:abc",false);
guard("http://127.0.0.1/x.mp4",false);
guard("http://localhost/x.mp4",false);
guard("http://169.254.169.254/latest/meta-data",false);
guard("http://192.168.1.5/x.mp4",false);
guard("https://example.com/f.mp4?api_key=secret",false);
guard("https://example.com/file.torrent",false);
/* The batch omitted these; the project's own guard already covered them. */
guard("http://100.64.0.1/x.mp4",false);
guard("http://[fd00::1]/x.mp4",false);
guard("http://[fe80::1]/x.mp4",false);
guard("http://[::ffff:127.0.0.1]/x.mp4",false);
guard("http://198.18.0.1/x.mp4",false);
guard("http://addon/x.mp4",false);

console.log("\n--- classification ---");
ok(policy.classifyUrl("https://x.test/a.m3u8")==="hls-playlist","m3u8 classified as an HLS playlist");
ok(policy.classifyUrl("https://x.test/b.mpd")==="dash-manifest","mpd classified as a DASH manifest");
ok(policy.classifyUrl("https://x.test/c.mp4")==="direct-file","mp4 classified as a direct file");
ok(policy.classifyUrl("https://x.test/search?q=a")==="search-page","a query page classified as search");

console.log("\n--- player: chrome never strands the viewer ---");
const player=req("lib/global-player");
const idle=player.createChromeIdleController({idleMs:400});
const wait=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
  idle.arm();
  await wait(520);
  ok(idle.isHidden()===true,"the chrome hides on idle");
  idle.hold("pointer");
  await wait(520);
  ok(idle.isHidden()===false,"a resting pointer keeps the controls on screen");
  idle.release("pointer");
  await wait(520);
  ok(idle.isHidden()===true,"releasing lets them hide");
  idle.hold("paused");
  await wait(520);
  ok(idle.isHidden()===false,"a paused film keeps the controls up");
  idle.releaseAll();
  await wait(520);
  ok(idle.isHidden()===true,"releaseAll restores normal hiding");
  idle.hold("pointer",300);
  await wait(420);
  ok(idle.isHeld()===false,"an un-released hold expires instead of pinning the bar forever");
  idle.destroy();

  console.log("\n--- player: one mount at a time ---");
  const torn=[];
  const owner=player.createPlaybackOwner({teardown:t=>torn.push(t.id)});
  const a=owner.begin({url:"a"}); const b=owner.begin({url:"b"});
  ok(!owner.isCurrent(a)&&owner.isCurrent(b),"a newer mount supersedes an older one");
  ok(owner.end(a)===false,"a stale mount cannot end playback");
  ok(owner.isCurrent(b),"the newer film survives the dying one");
  ok(torn.length===0,"nothing was torn down by the stale mount");
  owner.end(b);
  ok(torn.length===1,"the current mount tears down exactly once");

  console.log("\n--- player: media kinds ---");
  ok(player.mediaKindForUrl("https://x/a.m3u8?t=1")==="hls","query strings do not hide the HLS extension");
  ok(player.mediaKindForUrl("https://x/a.mpd")==="dash","mpd detected as DASH");
  ok(player.mediaKindForUrl("https://x/a.mp4")==="direct","mp4 detected as direct");
  const levels=player.describeLevels([{id:0,height:1080},{id:1,height:2160},{id:2,height:720}]);
  ok(levels[0].height===2160,"levels sort highest first");
  ok(player.chooseStartLevel([{id:0,height:2160},{id:1,height:1080}],1080,"cap")===1,"a 1080p screen does not start at 4K");

  console.log("\n--- bridge: nothing unplayable crosses into the player ---");
  const bridge=req("lib/global-player-bridge");
  ok(bridge.toPlaybackRequest({url:"https://x/a.mp4"})!==null,"a direct file converts");
  ok(bridge.toPlaybackRequest({url:"magnet:?xt=urn:btih:x"})===null,"a magnet is refused");
  ok(bridge.toPlaybackRequest({url:"https://x/a.torrent"})===null,"a .torrent is refused");
  ok(bridge.toPlaybackRequest({url:"https://x/a.mp4",behaviorHints:{notWebReady:true}})===null,"notWebReady is refused");
  ok(bridge.toPlaybackRequest({url:"https://x/a.mp4",bytes:6e9})===null,"over 5 GB is refused");
  ok(bridge.toPlaybackRequest({url:"https://x/a.mp4",proxyHeaders:{a:1}})===null,"combined proxy headers are refused");
  ok(bridge.toPlaybackRequest({url:"https://x/watch/1"})===null,"a watch page is not a stream");
  ok(bridge.rankPlaybackRequests([
    {url:"https://x/a.mp4",quality:"720p",identityScore:10},
    {url:"https://x/a.mp4",quality:"720p",identityScore:10},
    {url:"https://x/b.mp4",quality:"1080p",identityScore:90}
  ]).length===2,"duplicate URLs collapse");

  console.log("\n--- bridge: a dead host is walked past ---");
  const tried=[];
  const session=bridge.createPlaySession({play:async r=>{ tried.push(r.url); return r.url.includes("bad")?{ok:false}:{ok:true}; }});
  const out=await session.start([{url:"https://x/bad.mp4",identityScore:90},{url:"https://x/good.mp4",identityScore:10}]);
  ok(out.ok&&out.request.url==="https://x/good.mp4","the session lands on the stream that plays");
  ok(tried.length===2,"it tried the dead one first");

  console.log("\n--- api handler answers both platforms ---");
  const handler=loaded["api/global-resolve"];
  const mkRes=()=>{ const res={statusCode:200,headers:{},setHeader(k,v){this.headers[k]=v;},end(b){this.body=b;}};
    res.status=c=>{res.statusCode=c;return res;}; res.json=p=>{res.body=JSON.stringify(p);return res;}; return res; };
  const netlifyRes=mkRes();
  await handler({method:"GET",query:{title:"Inception",year:"2010",type:"movie"}},netlifyRes);
  ok(netlifyRes.statusCode===200,"a GET with a title answers 200");
  ok((netlifyRes.body||"").includes("identity"),"the body carries the resolved identity");
  const nodeRes={statusCode:0,setHeader(){},end(b){this.body=b;}};
  await handler({method:"DELETE"},nodeRes);
  ok(nodeRes.statusCode===405,"a bad method answers 405 on a bare Node response");
  const badRes=mkRes();
  await handler({method:"GET",query:{}},badRes);
  ok(badRes.statusCode===400,"a request with no identity answers 400");

  console.log("\n--- index.html: the join is wired, not just present ---");
  const html=fs.readFileSync(path.join(ROOT,"index.html"),"utf8");
  const src=html.slice(html.indexOf("<script>")+8, html.lastIndexOf("</script>"));
  ok(src.includes('const GLOBAL_RESOLVE_ENDPOINT="/api/global-resolve"'),"the app knows the resolver endpoint");
  ok(src.includes("async function findGlobalStreams"),"findGlobalStreams exists");
  ok(src.includes("globalResultToVariant"),"resolver results convert to player variants");
  ok(!/feedsSilent/.test(src),"no invented flags are referenced");
  /* Regression: startOwnEngine takes (stage,url,sourceValue,...) and returns a
     promise. Calling it with a variant object left the stage blank. */
  ok(/startOwnEngine\(stage,variant\.url,source,performance\.now\(\),0,\{kind:variant\.kind\}\)/.test(src),
     "the global mount path uses startOwnEngine's real signature");
  ok(src.includes("staleStage.innerHTML=\"\""),"a recovering pass clears the previous error card");
  ok(!/drawPlayer\(prevP\);\s*\n\s*return;/.test(src),
     "the no-feed path does not recurse into drawPlayer (it paints out of order)");
  ok(html.includes('id="playerStage" data-player'),"the player stage carries the data-player mount point");

  console.log(`\n${n} passed, ${failed} failed`);
  if(failed) process.exit(1);
})().catch(e=>{ console.error("FAILED",e.message); process.exit(1); });
