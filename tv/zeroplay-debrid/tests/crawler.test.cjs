const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const root=path.resolve(__dirname,'..');
const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
const script=html.slice(html.indexOf('<script>')+8,html.lastIndexOf('</script>'));
function between(a,b){const i=script.indexOf(a);assert(i>=0,a);const j=script.indexOf(b,i+a.length);assert(j>i,b);return script.slice(i,j);}
const manifest=(id,name)=>({id,name,version:'1',resources:['stream','catalog','meta'],types:['movie','series'],idPrefixes:['tt'],catalogs:[{type:'movie',id:'top',extra:[{name:'search'}]}]});
const cinemetaManifest={id:'com.linvo.cinemeta',name:'Cinemeta',version:'3',resources:['catalog','meta','addon_catalog'],types:['movie','series'],idPrefixes:['tt'],addonCatalogs:[{type:'all',id:'community'},{type:'all',id:'official'}],catalogs:[]};
function response(data,status=200){return {ok:status>=200&&status<300,status,headers:{get(){return null;}},async json(){return data;},async text(){return typeof data==='string'?data:JSON.stringify(data);}};}
const fetchCalls=[];
async function fakeFetch(url){
  const u=String(url); fetchCalls.push(u);
  if(u.startsWith('/api/extract')) return response({ok:false},404);
  if(u==='https://v3-cinemeta.strem.io/manifest.json') return response(cinemetaManifest);
  if(u.includes('v3-cinemeta.strem.io/addon_catalog/')) return response({addons:[{transportUrl:'https://graph.example/manifest.json',manifest:manifest('graph','Graph Addon')}]});
  if(u.includes('stremio-addons.net/api/v0/addons')) return response({addons:[{manifestUrl:'https://listed.example/manifest.json',manifest:manifest('listed','Listed Addon'),stars:42,categories:[{slug:'movies'}],instances:[]}],pagination:{hasNextPage:false}});
  if(u.includes('api.github.com/search/repositories')) return response({total_count:1,items:[{full_name:'Example/GHAddon',name:'GHAddon',html_url:'https://github.com/Example/GHAddon',description:'A Stremio addon server',homepage:'https://gh-addon.example',topics:['stremio-addon'],stargazers_count:99,default_branch:'main',has_pages:false}]});
  if(u==='https://gh-addon.example/manifest.json') return response(manifest('gh','GitHub Hosted Addon'));
  if(u.includes('raw.githubusercontent.com/Example/GHAddon/main/README.md')) return response('Install https://gh-addon.example/manifest.json');
  if(u.includes('site.example/manifest.json')) return response({error:'not found'},404);
  return response({},404);
}
const context={
  console,URL,Set,Map,Date,Math,Number,String,Array,Object,RegExp,Promise,AbortController,setTimeout,clearTimeout,setInterval,clearInterval,
  fetch:fakeFetch,
  document:{getElementById(){return null;},activeElement:null},
  settings:{providers:[],providerTombstones:{},addons:[],sites:[],customSources:[],disabledSources:[],health:{},feed:{url:'',auto:true,intervalMin:15,lastCheck:0,healthAuto:true},discovery:{autoStart:true,intervalSec:90,crawlers:{catalogGraph:true,stremioAddons:true,github:true,githubProbe:true,candidateIdentification:true,feeds:true,websites:true,health:true},githubQueries:['topic:stremio-addon'],cursors:{},seeds:[],stats:{cycles:0}},stream:{quality:'auto'}},
  saveSettings(){},dlog(){},toast(){},routeNow(){return 'home';},renderSources(){},prettyHostName(h){return String(h).split('.')[0];},parseFeedData(){return [];},sourceSignature(s){return JSON.stringify(s);},isDirectMedia(u){return /\.(mp4|m3u8)$/i.test(String(u));},runHealthChecks:async()=>7,
  async registerWebsiteSource(raw){const u=new URL(raw);context.settings.sites.push({id:'site:'+u.hostname,name:'Example Site',base:u.origin,home:u.href,enabled:true,searchTemplate:u.origin+'/search?q={q}'});return {origin:u.origin,url:u.href,searchTemplate:u.origin+'/search?q={q}'};}
};
context.globalThis=context;vm.createContext(context);
vm.runInContext(between('const PROVIDER_CATEGORIES=','function streamIdsForAddon'),context,{filename:'discovery-extract.js'});
let n=0;const ok=(c,l)=>{assert(c,l);n++;console.log('PASS',l);};
(async()=>{
  const totals=await context.runDiscoveryCycle('test');
  const providers=context.settings.providers;
  ok(context.discoveryCfg().stats.cycles===1,'one continuous cycle completed');
  ok(providers.some(p=>p.name==='Cinemeta'&&p.status==='installed'&&p.categories.includes('discovery')),'Cinemeta server identified from manifest');
  ok(providers.some(p=>p.name==='Graph Addon'&&p.status==='installed'),'recursive catalogue graph addon installed after identification');
  ok(providers.some(p=>p.name==='Listed Addon'&&p.status==='installed'),'stremio-addons.net addon installed after identification');
  const gh=providers.find(p=>p.repoUrl==='https://github.com/Example/GHAddon');
  ok(gh&&gh.status==='installed'&&gh.name==='GitHub Hosted Addon','GitHub project probed, identified and installed');
  ok(gh.identification.supports.streams&&gh.identification.identifierModes.includes('imdb-id'),'GitHub server capabilities/identifier modes recorded');
  ok(context.settings.addons.length>=3,'identified stream servers synced for later playback search');
  ok(fetchCalls.some(u=>u.includes('api.github.com/search/repositories')),'GitHub crawler ran');
  ok(fetchCalls.some(u=>u.includes('/addon_catalog/all/community.json')),'catalogue graph crawler ran');
  ok(totals.errors.length===0,'mocked crawler cycle had no errors');
  const siteProvider=await context.addDiscoverySeed('https://site.example/watch');
  ok(siteProvider&&siteProvider.kind==='website-lookup'&&siteProvider.status==='installed','website seed identified as searchable lookup server');
  ok(siteProvider.identification.searchable&&siteProvider.identification.supports.episodeLookup,'website identification records title/episode search capability');
  console.log(`\n${n} crawler checks passed`);
})().catch(e=>{console.error(e);process.exit(1);});
