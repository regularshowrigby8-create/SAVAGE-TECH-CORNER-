'use strict';
(() => {
  const STORAGE='zerostreams-web-v1';
  const defaults=()=>({favorites:[],planned:[],history:[],positions:{},searches:[],collections:{},notInterested:[],profiles:{},activeProfile:'Default',settings:{region:'PH',gain:1,theme:'dark',uiLayout:'youtube',source:'vidstuck'},hasKey:true,version:'2.2.2 Web'});
  let saved=defaults(),playing,iframe,idleTimer,lastProgress=0;
  try{const parsed=JSON.parse(localStorage.getItem(STORAGE)||'null');if(parsed&&typeof parsed==='object')saved={...saved,...parsed,settings:{...saved.settings,...parsed.settings},version:'2.2.2 Web'};}catch{}
  for(const name of ['favorites','planned','history','searches','notInterested'])if(!Array.isArray(saved[name]))saved[name]=[];if(!saved.profiles||typeof saved.profiles!=='object'||Array.isArray(saved.profiles))saved.profiles={};if(!saved.activeProfile)saved.activeProfile='Default';
  for(const name of ['positions','collections'])if(!saved[name]||typeof saved[name]!=='object'||Array.isArray(saved[name]))saved[name]={};
  if(!['vidstuck','zxcstream','embedwave'].includes(saved.settings.source)){saved.settings.source='vidstuck';try{localStorage.setItem(STORAGE,JSON.stringify(saved));}catch{}}
  const copy=()=>JSON.parse(JSON.stringify(saved));
  function persist(){try{localStorage.setItem(STORAGE,JSON.stringify(saved));}catch{throw Error('Your browser could not save the library. Storage may be full or disabled.');}}
  function key(item){return item.type+':'+item.id;}
  function clean(value){if(!value||!['movie','tv'].includes(value.type)||!Number.isSafeInteger(value.id)||value.id<1)throw Error('Invalid title');const item={id:value.id,type:value.type,title:String(value.title||'Untitled').slice(0,300),overview:String(value.overview||'').slice(0,10000),date:String(value.date||'').slice(0,10),rating:Math.max(0,Math.min(10,Number(value.rating)||0)),popularity:Math.max(0,Number(value.popularity)||0),voteCount:Math.max(0,Number(value.voteCount)||0),genreIds:Array.isArray(value.genreIds)?value.genreIds.map(Number).filter(x=>Number.isSafeInteger(x)&&x>0).slice(0,20):[],originalLanguage:/^[a-z]{2}$/.test(value.originalLanguage||'')?value.originalLanguage:'',originCountry:Array.isArray(value.originCountry)?value.originCountry.filter(x=>/^[A-Z]{2}$/.test(x)).slice(0,10):[]};for(const field of ['poster','backdrop'])item[field]=/^https:\/\/image\.tmdb\.org\/t\/p\/(w185|w342|w500|w780|w1280)\/[A-Za-z\d_.-]+$/.test(value[field]||'')?value[field]:'';return item;}
  function record(item,position={}){item=clean(item);const season=position.season===0?0:Number(position.season)||1,episode=Number(position.episode)||1;const historyKey=key(item)+(item.type==='tv'?':'+(position.season===0?0:season)+':'+episode:'');const old=saved.history.find(row=>row.historyKey===historyKey);let row={...item,...(old||{}),...position,season:position.season===0?0:season,episode,historyKey,at:Date.now()};if(old?.percent>=95&&!position.duration)row={...item,season,episode,historyKey,at:Date.now(),timestamp:0,duration:0,percent:0};if(!(Number(row.timestamp)>0)&&!position.duration)row={...row,timestamp:1,duration:0,percent:0};saved.history=[row,...saved.history.filter(x=>x.historyKey!==historyKey)].slice(0,100);saved.positions[key(item)]={...row};persist();}
  function personal(){return {favorites:saved.favorites,planned:saved.planned,history:saved.history,positions:saved.positions,searches:saved.searches,collections:saved.collections,notInterested:saved.notInterested};}
  function applyPersonal(value={}){saved.favorites=Array.isArray(value.favorites)?value.favorites:[];saved.planned=Array.isArray(value.planned)?value.planned:[];saved.history=Array.isArray(value.history)?value.history:[];saved.positions=value.positions&&typeof value.positions==='object'?value.positions:{};saved.searches=Array.isArray(value.searches)?value.searches:[];saved.collections=value.collections&&typeof value.collections==='object'?value.collections:{};saved.notInterested=Array.isArray(value.notInterested)?value.notInterested:[];}
  function validProfile(name){return /^[A-Za-z0-9 _-]{1,24}$/.test(name)&&!['__proto__','constructor','prototype'].includes(name);}
  const listeners=new Set();
  const refresh=()=>listeners.forEach(callback=>callback());
  const dialog=document.getElementById('web-player');
  function activity(){dialog.classList.remove('player-idle');clearTimeout(idleTimer);idleTimer=setTimeout(()=>dialog.classList.add('player-idle'),3000);}
  function close(){if(iframe){iframe.src='about:blank';iframe.remove();iframe=null;}playing=null;clearTimeout(idleTimer);dialog.close();refresh();}
  document.getElementById('close-player').addEventListener('click',close);
  dialog.addEventListener('cancel',event=>{event.preventDefault();close();});
  document.getElementById('player-exit-always').addEventListener('click',activity);
  dialog.addEventListener('pointermove',activity);dialog.addEventListener('pointerdown',activity);dialog.addEventListener('keydown',activity);
  window.addEventListener('message',event=>{
    if(!window.playbackSources.trusted(event.origin)||event.source!==iframe?.contentWindow||!playing||Date.now()-lastProgress<2500)return;
    try{const raw=typeof event.data==='string'?JSON.parse(event.data):event.data;const data=window.playbackSources.normalize(raw);if(!data||JSON.stringify(data).length>4096||String(data.id)!==String(playing.id)||data.type!==playing.type)return;
      const timestamp=Number(data.timestamp),duration=Number(data.duration);if(!Number.isFinite(timestamp)||!Number.isFinite(duration)||timestamp<0||duration<=0||duration>604800||timestamp>duration+10)return;
      let ep={};if(playing.type==='tv'){const season=Number(data.season),episode=Number(data.episode);if(!Number.isInteger(season)||season<0||season>1000||!Number.isInteger(episode)||episode<1||episode>10000)return;ep={season,episode};}
      lastProgress=Date.now();record(playing,{...ep,timestamp:Math.min(timestamp,duration),duration,percent:Math.min(100,Math.max(0,timestamp/duration*100))});
    }catch{}
  });
  window.zero={
    async api(path,params={}){const query=new URLSearchParams({path});for(const [name,value]of Object.entries(params))query.set(name,String(value));const response=await fetch('/api/tmdb?'+query.toString(),{credentials:'same-origin'});const data=await response.json();if(!response.ok)throw Error(data.error||'The catalog is unavailable. Try again.');return data;},
    async state(){return copy();},
    async change(action,value){
      if(['favorites','planned'].includes(action)){value=clean(value);saved[action]=saved[action].some(x=>key(x)===key(value))?saved[action].filter(x=>key(x)!==key(value)):[value,...saved[action]].slice(0,300);}
      else if(action==='search'){const query=String(value||'').trim().slice(0,200);if(query)saved.searches=[query,...saved.searches.filter(x=>x.toLowerCase()!==query.toLowerCase())].slice(0,30);}
      else if(action==='remove-search')saved.searches=saved.searches.filter(x=>x!==value);
      else if(action==='clear-searches')saved.searches=[];
      else if(action==='remove-history')saved.history=saved.history.filter(x=>x.historyKey!==value);
      else if(action==='clear-history'){saved.history=[];saved.positions={};}
      else if(action==='not-interested'){const id=String(value||'');if(!/^(movie|tv):[1-9]\d*$/.test(id))throw Error('Invalid title');saved.notInterested=saved.notInterested.includes(id)?saved.notInterested.filter(x=>x!==id):[id,...saved.notInterested].slice(0,500);}
      else if(action==='mark-watched'){const id=String(value?.key||'');if(!/^(movie|tv):[1-9]\d*$/.test(id))throw Error('Invalid title');if(value?.watched===false)delete saved.positions[id];else{const source=saved.positions[id]||saved.history.find(x=>key(x)===id)||saved.favorites.find(x=>key(x)===id)||saved.planned.find(x=>key(x)===id)||{id:Number(id.split(':')[1]),type:id.split(':')[0],title:'Watched title',overview:'',date:'',rating:0,poster:'',backdrop:''};saved.positions[id]={...source,timestamp:1,duration:1,percent:100,at:Date.now()};}}
      else if(action==='profile-add'){const name=String(value||'').trim();if(!validProfile(name))throw Error('Use 1–24 letters, numbers, spaces, _ or -');if(name!==saved.activeProfile&&!saved.profiles[name])saved.profiles[name]={favorites:[],planned:[],history:[],positions:{},searches:[],collections:{},notInterested:[]};}
      else if(action==='profile-switch'){const name=String(value||'').trim();if(!validProfile(name))throw Error('Invalid profile');saved.profiles[saved.activeProfile]=personal();applyPersonal(saved.profiles[name]||{favorites:[],planned:[],history:[],positions:{},searches:[],collections:{},notInterested:[]});saved.activeProfile=name;}
      else if(action==='profile-remove'){const name=String(value||'').trim();if(name==='Default'||name===saved.activeProfile)throw Error('Cannot delete Default or the active profile');delete saved.profiles[name];}
      else if(action==='settings'){if(!['PH','US','GB','CA','AU','IN','JP'].includes(value?.region))throw Error('Invalid region');if(!['dark','light'].includes(value.theme)||!['vidstuck','zxcstream','embedwave'].includes(value.source))throw Error('Invalid appearance or playback source');saved.settings={region:value.region,gain:1,uiLayout:['youtube','google','flix','classic'].includes(value.uiLayout)?value.uiLayout:'youtube',theme:value.theme,source:value.source};}
      else if(action==='collection'){const name=String(value?.name||'').trim().slice(0,60);if(!name||['__proto__','constructor','prototype'].includes(name))throw Error('Invalid collection name');const item=clean(value.item),rows=saved.collections[name]||[];if(!Array.isArray(rows))throw Error('Invalid collection');saved.collections[name]=rows.some(x=>key(x)===key(item))?rows.filter(x=>key(x)!==key(item)):[item,...rows].slice(0,300);}
      else throw Error('Unsupported library action');persist();return copy();
    },
    async play(value,selected){
      const item=clean(value);let position=selected||saved.positions[key(item)]||{};
      if(selected&&(!Number.isInteger(selected.season)||selected.season<0||selected.season>1000||!Number.isInteger(selected.episode)||selected.episode<1||selected.episode>10000))throw Error('Choose a valid episode');
      const previous=saved.positions[key(item)];if(selected&&previous&&selected.season===previous.season&&selected.episode===previous.episode)position=previous;
      if(iframe){iframe.src='about:blank';iframe.remove();}playing=item;lastProgress=0;record(item,position);
      const url=new URL(window.playbackSources.url(item,position,saved.settings.source||'vidstuck'));
      iframe=document.createElement('iframe');iframe.src=url.href;iframe.title=item.title+' player';iframe.allow='autoplay; fullscreen; encrypted-media; picture-in-picture';iframe.allowFullscreen=true;iframe.referrerPolicy=url.hostname==='embedwave.cc'?'no-referrer':'strict-origin-when-cross-origin';
      document.getElementById('player-title').textContent=item.title;document.getElementById('player-stage').appendChild(iframe);if(!dialog.open)dialog.showModal();activity();refresh();
    },
    async external(value){const url=new URL(value);if(url.protocol!=='https:'||!['www.youtube.com','www.themoviedb.org'].includes(url.hostname))throw Error('Unsupported link');window.open(url.href,'_blank','noopener,noreferrer');},
    onRefresh(callback){listeners.add(callback);return()=>listeners.delete(callback);}
  };
})();
