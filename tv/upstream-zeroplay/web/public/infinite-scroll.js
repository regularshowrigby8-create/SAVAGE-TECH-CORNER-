'use strict';
(() => {
  const content=document.getElementById('content');
  if(!content)return;
  let loading=false,phase='idle',savedGrid=null,savedPaging=null,loadContext='',scheduled=false,blockedUntil=0,retries=0,scrollIntentUntil=0,lastScrollY=window.scrollY;
  const contextKey=()=>[document.documentElement.dataset.section||'',document.getElementById('heading')?.textContent||'',document.getElementById('query')?.value.trim()||'',document.getElementById('web-filter-bar')?.textContent||''].join('|');
  function hidePaging(pager){if(!pager)return;pager.hidden=true;pager.setAttribute('aria-hidden','true');}
  function nearBottom(){return window.scrollY>0&&document.documentElement.scrollHeight-(window.scrollY+window.innerHeight)<500;}
  function endMarker(){const pager=content.querySelector('.paging');if(!pager||pager.querySelector('[data-action="next-page"]')||content.querySelector('.infinite-end'))return;const marker=document.createElement('div');marker.className='infinite-end';marker.textContent='You reached the end of these results.';pager.after(marker);}
  function reset(keepRetry=false){loading=false;phase='idle';savedGrid=null;savedPaging=null;loadContext='';content.style.minHeight='';if(!keepRetry)retries=0;}
  function restoreWhileLoading(){if(phase!=='waiting-skeleton'||!savedGrid||!content.querySelector('.skeleton'))return false;const loader=document.createElement('div');loader.className='paging';loader.id='infinite-loader';loader.setAttribute('role','status');loader.textContent=retries?'Retrying…':'Loading more titles…';content.replaceChildren(savedGrid,loader);phase='waiting-page';return true;}
  function mergeLoadedPage(){
    if(phase!=='waiting-page')return false;
    if(loadContext!==contextKey()){reset();return true;}
    const newGrid=content.querySelector('.grid'),pager=content.querySelector('.paging');
    if(newGrid&&pager&&newGrid!==savedGrid){
      const existing=new Set([...savedGrid.querySelectorAll('[data-detail],[data-web-open]')].map(node=>node.dataset.detail||node.dataset.webOpen));
      for(const cardNode of [...newGrid.children]){const id=cardNode.dataset?.detail||cardNode.dataset?.webOpen||'';if(!id||!existing.has(id)){if(id)existing.add(id);savedGrid.appendChild(cardNode);}}
      content.replaceChildren(savedGrid,pager);hidePaging(pager);reset();endMarker();scheduleCheck();return true;
    }
    const empty=content.querySelector('.empty');
    if(empty&&!content.querySelector('.skeleton')){
      const retryPaging=pager||savedPaging;content.replaceChildren(savedGrid);if(retryPaging){content.appendChild(retryPaging);hidePaging(retryPaging);}retries++;
      if(retries<=2&&retryPaging?.querySelector('[data-action="next-page"]')){blockedUntil=Date.now()+900*retries;reset(true);setTimeout(scheduleCheck,950*retries);}else{blockedUntil=Date.now()+5000;reset();const note=document.createElement('div');note.className='infinite-end';note.textContent='Could not load more titles. Scroll again to retry.';content.appendChild(note);}return true;
    }
    return false;
  }
  function canAppendCatalog(){return typeof category!=='undefined'&&typeof page!=='undefined'&&(category==='Movies'||category==='Series');}
  async function appendCatalogPage(grid,pager){
    const section=category,nextPage=page+1,request=window.zero22CatalogRequest?window.zero22CatalogRequest(section,nextPage):null,type=request?.type||(section==='Series'?'tv':'movie'),path=request?.path||(section==='Series'?'/tv/popular':'/movie/popular'),requestParams=request?.params||{page:nextPage};
    scrollIntentUntil=0;loading=true;loadContext=contextKey();savedPaging=pager.cloneNode(true);pager.hidden=false;pager.removeAttribute('aria-hidden');pager.id='infinite-loader';pager.setAttribute('role','status');pager.textContent='Loading more titles…';
    try{
      const data=await api(path,requestParams);
      if(section!==category||loadContext!==contextKey()){reset();return;}
      const existing=new Set([...grid.querySelectorAll('[data-detail],[data-web-open]')].map(node=>node.dataset.detail||node.dataset.webOpen));
      let html='';
      for(const item of list(data,type)){const id=key(item);if(existing.has(id))continue;existing.add(id);html+=card(item);}
      if(html)grid.insertAdjacentHTML('beforeend',html);
      page=nextPage;
      const holder=document.createElement('div');holder.innerHTML=paging(data.total_pages);const nextPager=holder.firstElementChild;pager.replaceWith(nextPager);hidePaging(nextPager);
      status('Popular '+(type==='tv'?'series':'movies')+' · '+grid.children.length+' titles loaded');
      reset();endMarker();
    }catch(error){
      const restore=savedPaging;pager.replaceWith(restore);hidePaging(restore);blockedUntil=Date.now()+3000;reset();const note=document.createElement('div');note.className='infinite-end';note.textContent='Could not load more titles. Scroll again to retry.';restore.after(note);
    }
  }
  function armScrollIntent(){if(!loading)scrollIntentUntil=Date.now()+700;}
  function loadMore(){if(Date.now()>scrollIntentUntil||loading||Date.now()<blockedUntil||document.querySelector('dialog[open]')||!nearBottom())return;const grid=content.querySelector('.grid'),pager=content.querySelector('.paging'),next=pager?.querySelector('[data-action="next-page"]');if(!grid||!pager||!next){endMarker();return;}if(canAppendCatalog()){appendCatalogPage(grid,pager);return;}scrollIntentUntil=0;loading=true;phase='waiting-skeleton';savedGrid=grid;savedPaging=pager.cloneNode(true);loadContext=contextKey();content.style.minHeight=Math.max(content.offsetHeight,window.innerHeight)+'px';const top=window.scrollY;next.click();window.scrollTo({top,behavior:'auto'});}
  function scheduleCheck(){if(scheduled)return;scheduled=true;requestAnimationFrame(()=>{scheduled=false;const pager=content.querySelector('.paging');if(pager&&pager.id!=='infinite-loader')hidePaging(pager);loadMore();});}
  function resetTabPosition(){scrollIntentUntil=0;blockedUntil=Date.now()+500;lastScrollY=0;window.scrollTo({top:0,behavior:'auto'});}
  new MutationObserver(()=>{if(loading){if(phase==='waiting-skeleton')restoreWhileLoading();if(phase==='waiting-page')mergeLoadedPage();}if(!loading){const pager=content.querySelector('.paging');if(pager)hidePaging(pager);endMarker();}scheduleCheck();}).observe(content,{childList:true,subtree:false});
  document.addEventListener('click',event=>{if(event.target.closest('[data-nav],.brand'))resetTabPosition();},true);
  window.addEventListener('scroll',()=>{const y=window.scrollY;if(y>lastScrollY+2){armScrollIntent();scheduleCheck();}lastScrollY=y;},{passive:true});
  window.addEventListener('resize',scheduleCheck,{passive:true});
  scheduleCheck();
})();
