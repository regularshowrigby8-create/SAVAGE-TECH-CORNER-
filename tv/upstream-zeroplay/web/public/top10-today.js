'use strict';
(() => {
  if(window.__zeroWebTop10Today)return;
  window.__zeroWebTop10Today=true;

  const SELECTOR='[data-zero-web-top10]';
  let cached=[],cachedAt=0,loading=null,scheduled=false;

  function onHome(){return document.documentElement.dataset.section==='Home'||(typeof category!=='undefined'&&category==='Home');}
  function ref(item){return item.type+':'+item.id;}

  async function dailyTop10(force=false){
    if(!force&&cached.length&&Date.now()-cachedAt<300000)return cached;
    if(loading)return loading;
    loading=(async()=>{
      const data=await window.zero.api('/trending/all/day');
      const items=(data?.results||[]).map(raw=>normalize(raw)).filter(item=>item&&item.poster).slice(0,10);
      for(const item of items)titles.set(key(item),item);
      cached=items;
      cachedAt=Date.now();
      return items;
    })();
    try{return await loading;}finally{loading=null;}
  }

  function markup(items){
    return `<section class="row-section zero-web-top10" data-zero-web-top10="1"><div class="section-head"><h3>TOP 10 TODAY</h3><small>Updated daily</small></div><div class="zero-web-top10-rail" aria-label="Top 10 Today">${items.map((item,index)=>`<button type="button" class="zero-web-top10-card" data-detail="${ref(item)}" aria-label="Number ${index+1}, ${escape(item.title)}"><span class="zero-web-top10-rank" aria-hidden="true">${index+1}</span><span class="zero-web-top10-poster">${item.poster?`<img loading="lazy" decoding="async" src="${escape(item.poster)}" alt="${escape(item.title)}">`:'<span class="poster-empty">Z</span>'}</span></button>`).join('')}</div></section>`;
  }

  async function ensure(){
    scheduled=false;
    if(!onHome())return;
    const content=document.querySelector('#content'),hero=document.querySelector('#hero');
    if(!content||!hero||content.querySelector(SELECTOR))return;
    try{
      const items=await dailyTop10();
      const currentHero=document.querySelector('#hero');
      if(!items.length||!onHome()||!currentHero||document.querySelector(SELECTOR))return;
      currentHero.insertAdjacentHTML('afterend',markup(items));
    }catch{}
  }

  function schedule(){if(scheduled)return;scheduled=true;const run=window.requestAnimationFrame||((fn)=>window.setTimeout(fn,0));run(()=>ensure());}
  const content=document.querySelector('#content');
  if(content)new MutationObserver(schedule).observe(content,{childList:true,subtree:true});
  document.addEventListener('click',event=>{if(event.target.closest?.('[data-action="refresh"]')){cached=[];cachedAt=0;setTimeout(schedule,0);}},true);
  window.addEventListener('load',schedule,{once:true});
  schedule();
})();
