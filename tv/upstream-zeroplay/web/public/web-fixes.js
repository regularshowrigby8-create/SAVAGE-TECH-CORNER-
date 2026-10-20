'use strict';
(() => {
  const overlayIds=['web-player','settings','detail'];
  const allowedExternalHosts=new Set(['www.youtube.com','www.themoviedb.org','vidstuck.cc','embedwave.cc']);
  const allowedPlaybackSources=new Set(['vidstuck','embedwave']);
  let closingFromHistory=false;

  const topOpenDialog=()=>overlayIds.map(id=>document.getElementById(id)).find(node=>node?.open)||null;

  function closeDialogFromHistory(dialog){
    if(!dialog)return;
    closingFromHistory=true;
    try{
      if(dialog.id==='web-player'){
        const cancel=new Event('cancel',{cancelable:true});
        dialog.dispatchEvent(cancel);
      }else{
        dialog.close();
      }
    }finally{
      queueMicrotask(()=>{closingFromHistory=false;});
    }
  }

  const originalShowModal=HTMLDialogElement.prototype.showModal;
  HTMLDialogElement.prototype.showModal=function(){
    const wasOpen=this.open;
    originalShowModal.call(this);
    if(!wasOpen&&overlayIds.includes(this.id)){
      history.pushState({...(history.state||{}),zeroPlayOverlay:this.id},'',location.href);
    }
  };

  window.addEventListener('popstate',()=>{
    const open=topOpenDialog();
    if(open)closeDialogFromHistory(open);
  });

  document.addEventListener('click',event=>{
    const target=event.target.closest('#close-player,[data-action="close"],[data-action="close-settings"]');
    if(!target||closingFromHistory)return;
    const dialog=target.closest('dialog');
    if(!dialog?.open)return;
    if(history.state?.zeroPlayOverlay===dialog.id){
      event.preventDefault();
      event.stopImmediatePropagation();
      history.back();
    }
  },true);

  for(const id of overlayIds){
    const dialog=document.getElementById(id);
    if(!dialog)continue;
    dialog.addEventListener('cancel',event=>{
      if(closingFromHistory||history.state?.zeroPlayOverlay!==dialog.id)return;
      event.preventDefault();
      event.stopImmediatePropagation();
      history.back();
    },true);
  }

  const enforceWebSource=()=>{
    for(const option of document.querySelectorAll('option[value="vidsrc-sh"]'))option.remove();
    for(const select of document.querySelectorAll('#playback-source,.title-source')){
      if(!allowedPlaybackSources.has(select.value))select.value='vidstuck';
    }
  };
  enforceWebSource();
  new MutationObserver(enforceWebSource).observe(document.documentElement,{subtree:true,childList:true});

  const nativeOpen=window.open.bind(window);
  window.open=(value,target,features)=>{
    try{
      const url=new URL(value,location.href);
      if(url.origin===location.origin)return nativeOpen(url.href,target,features);
      if(url.protocol!=='https:'||!allowedExternalHosts.has(url.hostname))return null;
      return nativeOpen(url.href,target||'_blank',features||'noopener,noreferrer');
    }catch{return null;}
  };

  document.addEventListener('click',event=>{
    const link=event.target.closest('a[href]');
    if(!link)return;
    try{
      const url=new URL(link.href,location.href);
      if(url.origin===location.origin)return;
      if(url.protocol!=='https:'||!allowedExternalHosts.has(url.hostname)){
        event.preventDefault();
        event.stopImmediatePropagation();
        return;
      }
      link.rel='noopener noreferrer';
      if(!link.target)link.target='_blank';
    }catch{
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  },true);

  window.name='';

  const openSource=document.getElementById('open-player-source');
  if(openSource){
    openSource.addEventListener('click',()=>{
      const frame=document.querySelector('#player-stage iframe');
      if(!frame?.src)return;
      const url=new URL(frame.src);
      if(url.protocol!=='https:'||!window.playbackSources?.trusted(url.origin))return;
      window.open(url.href,'_blank','noopener,noreferrer');
    });
  }
})();
