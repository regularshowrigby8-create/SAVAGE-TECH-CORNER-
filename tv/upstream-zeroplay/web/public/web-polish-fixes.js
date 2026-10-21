'use strict';
(() => {
  const content=document.getElementById('content');
  const query=document.getElementById('query');
  const searchForm=document.getElementById('search-form');
  if(!content||!query||!searchForm)return;

  // Preserve already-loaded infinite-scroll DOM when Back closes a detail overlay.
  let historyRestoreUntil=0;
  window.addEventListener('popstate',()=>{historyRestoreUntil=Date.now()+250;});
  if(typeof window.navigate==='function'){
    const nativeNavigate=window.navigate;
    window.navigate=function(section,fresh=false){
      if(!fresh&&Date.now()<historyRestoreUntil&&document.documentElement.dataset.section===section)return Promise.resolve();
      return nativeNavigate.apply(this,arguments);
    };
  }
  if(typeof searchForm.requestSubmit==='function'){
    const nativeSubmit=searchForm.requestSubmit.bind(searchForm);
    searchForm.requestSubmit=function(){
      const routeQuery=new URLSearchParams(location.search).get('q')||'';
      if(Date.now()<historyRestoreUntil&&location.pathname==='/search'&&query.value===routeQuery)return;
      return nativeSubmit(...arguments);
    };
  }

  // Keep provider filters aligned with the Movie / TV provider mode selected on Home.
  let providerType='movie',syncing=false;
  document.addEventListener('click',event=>{
    const typeButton=event.target.closest('[data-provider-type]');
    if(typeButton)providerType=typeButton.dataset.providerType==='tv'?'tv':'movie';
    const provider=event.target.closest('[data-provider]');
    if(provider){
      const selected=document.querySelector('[data-provider-type].selected');
      if(selected)providerType=selected.dataset.providerType==='tv'?'tv':'movie';
    }
  },true);

  async function syncProviderFilter(){
    if(syncing||document.documentElement.dataset.section!=='Provider')return;
    const bar=document.getElementById('web-filter-bar');
    if(!bar||bar.dataset.mode===providerType)return;
    syncing=true;
    try{
      bar.dataset.mode=providerType;
      let saved={};
      try{saved=JSON.parse(localStorage.getItem('zeroplay-web-enhancements-v1')||'{}')?.filters?.[providerType]||{};}catch{}
      const genre=bar.querySelector('[data-web-filter="genre"]');
      if(genre){
        const data=await window.zero.api('/genre/'+providerType+'/list');
        const rows=(data.genres||[]).sort((a,b)=>String(a.name).localeCompare(String(b.name)));
        genre.innerHTML='<option value="">All genres</option>'+rows.map(row=>`<option value="${Number(row.id)}">${String(row.name).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}</option>`).join('');
        genre.value=String(saved.genre||'');
      }
      for(const name of ['year','rating','sort']){const control=bar.querySelector(`[data-web-filter="${name}"]`);if(control&&saved[name]!=null)control.value=String(saved[name]);}
    }catch{}finally{syncing=false;}
  }
  let scheduled=false;
  const schedule=()=>{if(scheduled)return;scheduled=true;requestAnimationFrame(()=>{scheduled=false;syncProviderFilter();});};
  new MutationObserver(schedule).observe(content,{childList:true,subtree:true});
  new MutationObserver(schedule).observe(document.documentElement,{attributes:true,attributeFilter:['data-section']});
  schedule();
})();
