'use strict';
(() => {
  const SECTION_PATHS={Home:'/',Movies:'/movies',Series:'/series',Search:'/search',Library:'/library',Provider:'/movies'};
  const pathFor=item=>`/${item?.type==='tv'?'tv':'movie'}/${Number(item?.id)}`;
  const cleanPath=path=>path.replace(/\/+$/,'')||'/';
  const isDetailPath=path=>/^\/(movie|tv)\/\d+$/.test(cleanPath(path));
  const detail=document.querySelector('#detail');
  let suppressedRef='';
  let suppressUntil=0;
  let closeGuardTimer=0;

  function currentSectionPath(){
    try{return SECTION_PATHS[category]||'/';}catch{return '/';}
  }

  function currentSectionName(){
    try{return category||'Home';}catch{return 'Home';}
  }

  function activeRef(){
    try{return activeDetail?`${activeDetail.type}:${activeDetail.id}`:'';}catch{return '';}
  }

  function updateShareLabels(root=document){
    for(const button of root.querySelectorAll?.('[data-share]')||[]) {
      button.textContent='↗ Share';
      button.title='Share this ZeroPlay title';
      button.setAttribute('aria-label','Share this title');
    }
  }

  function lookupItem(ref){
    try{
      const fromMap=titles.get(ref);
      if(fromMap)return fromMap;
    }catch{}
    try{
      if(activeDetail&&`${activeDetail.type}:${activeDetail.id}`===ref)return activeDetail;
    }catch{}
    return null;
  }

  async function shareItem(item){
    if(!item)return;
    const url=new URL(pathFor(item),location.origin).href;
    const payload={title:`${item.title} — ZeroPlay`,text:`Watch ${item.title} on ZeroPlay`,url};
    try{
      if(navigator.share){await navigator.share(payload);return;}
      if(navigator.clipboard?.writeText){await navigator.clipboard.writeText(url);toast('ZeroPlay link copied');return;}
      const input=document.createElement('input');
      input.value=url;input.style.position='fixed';input.style.opacity='0';document.body.appendChild(input);input.select();document.execCommand('copy');input.remove();toast('ZeroPlay link copied');
    }catch(error){if(error?.name!=='AbortError')toast('Could not share this link');}
  }

  function clearDetailRoute(){
    if(!isDetailPath(location.pathname))return;
    const section=currentSectionName();
    const next=currentSectionPath();
    try{history.replaceState({...(history.state||{}),zeroPlayRoute:section,zeroPlayScroll:window.scrollY},'',next);}catch{}
    document.title='ZeroPlay 2.2 — Discover your next great watch';
  }

  function suppressReopen(){
    suppressedRef=activeRef()||suppressedRef;
    suppressUntil=Date.now()+1400;
    clearTimeout(closeGuardTimer);
    const guard=()=>{
      if(Date.now()>=suppressUntil)return;
      if(detail?.open&&(!suppressedRef||activeRef()===suppressedRef)){
        try{detail.close();}catch{}
        try{++detailRequest;}catch{}
        clearDetailRoute();
      }
      closeGuardTimer=setTimeout(guard,90);
    };
    closeGuardTimer=setTimeout(guard,40);
  }

  /* Guard against an async route request finishing just after the user closes details. */
  try{
    const baseDetails=typeof details==='function'?details:null;
    if(baseDetails){
      details=async function(item){
        const ref=item?`${item.type}:${item.id}`:'';
        if(ref&&ref===suppressedRef&&Date.now()<suppressUntil)return;
        return baseDetails(item);
      };
      window.details=details;
    }
  }catch{}

  function closeDetailsAndRoute(){
    suppressReopen();
    clearDetailRoute();
  }

  document.addEventListener('click',async event=>{
    const target=event.target.closest('button,[data-share]');
    if(!target)return;
    if(target.dataset.share){
      event.preventDefault();event.stopImmediatePropagation();await shareItem(lookupItem(target.dataset.share));return;
    }
    if(target.dataset.action==='close'&&detail?.open)closeDetailsAndRoute();
  },true);

  detail?.addEventListener('close',closeDetailsAndRoute);
  detail?.addEventListener('cancel',()=>window.setTimeout(closeDetailsAndRoute,0));

  /* Browser Back should dismiss an open details dialog instead of leaving it over the previous route. */
  window.addEventListener('popstate',()=>{
    if(detail?.open&&!isDetailPath(location.pathname)){
      suppressReopen();
      try{detail.close();}catch{}
      try{++detailRequest;}catch{}
    }
  });

  const observer=new MutationObserver(records=>{
    for(const record of records)for(const node of record.addedNodes)if(node.nodeType===1)updateShareLabels(node);
  });
  observer.observe(document.documentElement,{childList:true,subtree:true});
  updateShareLabels();
})();
