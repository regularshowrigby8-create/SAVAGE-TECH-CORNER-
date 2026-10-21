'use strict';
(() => {
  if(window.__zeroPlayHeroTrailerV2)return;
  window.__zeroPlayHeroTrailerV2=true;

  const TRAILER_MS=28000;
  const ART_MS=9000;
  let sequence=0;
  let currentFrame=null;
  let muted=true;
  const mediaCache=new Map();

  function reducedMotion(){return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;}
  function performanceMode(){
    try{return Boolean(JSON.parse(localStorage.getItem('zeroplay-web-22-ui')||'{}').performanceMode);}catch{return false;}
  }
  function saveData(){return Boolean(navigator.connection?.saveData);}

  function heroPicks(){
    try{return (homeData?.trending||[]).filter(item=>item.backdrop).slice(0,6);}catch{return [];}
  }

  function activeHeroItem(){
    const picks=heroPicks();
    if(!picks.length)return null;
    const index=((Number(heroIndex)||0)%picks.length+picks.length)%picks.length;
    return picks[index];
  }

  function setCarouselTimer(ms){
    try{
      clearInterval(heroTimer);
      if(performanceMode())return;
      heroTimer=setInterval(()=>{
        if(category==='Home'&&!heroPaused&&!document.hidden&&!$('#detail')?.open&&$('#hero')?.getBoundingClientRect().bottom>100){
          heroIndex++;
          renderHero();
        }
      },ms);
    }catch{}
  }

  function trailerKey(data){
    const videos=Array.isArray(data?.videos?.results)?data.videos.results:Array.isArray(data?.results)?data.results:[];
    return videos
      .filter(video=>video?.site==='YouTube'&&/^[A-Za-z\d_-]{6,20}$/.test(video.key||'')&&['Trailer','Teaser'].includes(video.type))
      .sort((a,b)=>Number(b.official)-Number(a.official)||Number(b.type==='Trailer')-Number(a.type==='Trailer'))[0]?.key||'';
  }

  function logoPath(data,item){
    const logos=Array.isArray(data?.images?.logos)?data.images.logos:[];
    if(!logos.length)return '';
    const lang=String(item?.originalLanguage||'').toLowerCase();
    const scored=logos
      .filter(row=>/^\/[A-Za-z\d_.-]+$/.test(row?.file_path||''))
      .map(row=>({row,score:(row.iso_639_1==='en'?100:0)+(lang&&row.iso_639_1===lang?80:0)+(row.iso_639_1==null?40:0)+Math.min(30,Number(row.vote_average)||0)}))
      .sort((a,b)=>b.score-a.score);
    return scored[0]?.row?.file_path||'';
  }

  async function fetchHeroMedia(item){
    const cacheKey=`${item.type}:${item.id}`;
    if(mediaCache.has(cacheKey))return mediaCache.get(cacheKey);
    try{
      const data=await api(`/${item.type}/${item.id}`,{append_to_response:'videos,images',include_image_language:'en,null'});
      const value={trailer:trailerKey(data),logo:logoPath(data,item)};
      mediaCache.set(cacheKey,value);
      return value;
    }catch{
      const value={trailer:'',logo:''};
      mediaCache.set(cacheKey,value);
      return value;
    }
  }

  function youtubeUrl(key){
    const origin=encodeURIComponent(location.origin);
    return `https://www.youtube.com/embed/${key}?autoplay=1&mute=1&controls=0&loop=1&playlist=${key}&playsinline=1&rel=0&modestbranding=1&iv_load_policy=3&disablekb=1&fs=0&enablejsapi=1&origin=${origin}`;
  }

  function command(func,args=[]){
    if(!currentFrame?.contentWindow)return;
    currentFrame.contentWindow.postMessage(JSON.stringify({event:'command',func,args}),'https://www.youtube.com');
  }

  function cleanupHeroMedia(){
    const hero=document.querySelector('#hero');
    if(hero){
      hero.classList.remove('trailer-ready','has-title-logo');
      hero.querySelector('.hero-trailer')?.remove();
      hero.querySelector('.hero-audio')?.remove();
      hero.querySelector('.hero-trailer-badge')?.remove();
      hero.querySelector('.hero-title-logo')?.remove();
    }
    currentFrame=null;
    muted=true;
  }

  function installLogo(hero,item,path){
    if(!path)return;
    const content=hero.querySelector('.hero-content');
    const heading=content?.querySelector('h2');
    if(!content||!heading)return;
    const logo=document.createElement('img');
    logo.className='hero-title-logo';
    logo.src=`https://image.tmdb.org/t/p/w500${path}`;
    logo.alt=item.title;
    logo.decoding='async';
    logo.loading='eager';
    logo.addEventListener('load',()=>hero.classList.add('has-title-logo'),{once:true});
    logo.addEventListener('error',()=>logo.remove(),{once:true});
    heading.insertAdjacentElement('beforebegin',logo);
  }

  function installTrailer(hero,item,key,token){
    if(!key)return false;
    const layer=document.createElement('div');
    layer.className='hero-trailer';
    layer.setAttribute('aria-hidden','true');
    const frame=document.createElement('iframe');
    frame.title=`${item.title} trailer`;
    frame.allow='autoplay; encrypted-media; picture-in-picture';
    frame.referrerPolicy='strict-origin-when-cross-origin';
    frame.tabIndex=-1;
    frame.src=youtubeUrl(key);
    layer.appendChild(frame);

    const badge=document.createElement('span');
    badge.className='hero-trailer-badge';
    badge.textContent='TRAILER PREVIEW';

    const audio=document.createElement('button');
    audio.type='button';
    audio.className='hero-audio';
    audio.dataset.heroAudio='1';
    audio.setAttribute('aria-label','Turn trailer sound on');
    audio.title='Trailer sound';
    audio.textContent='🔇';

    const artNode=hero.querySelector('.hero-art');
    if(artNode)artNode.insertAdjacentElement('afterend',layer);else hero.prepend(layer);
    hero.appendChild(badge);
    hero.appendChild(audio);
    currentFrame=frame;
    muted=true;

    frame.addEventListener('load',()=>{
      if(token!==sequence||currentFrame!==frame||document.querySelector('#hero')!==hero)return;
      window.setTimeout(()=>{
        if(token===sequence&&currentFrame===frame&&document.querySelector('#hero')===hero){
          hero.classList.add('trailer-ready');
          command('playVideo');
        }
      },180);
    },{once:true});
    return true;
  }

  async function loadHeroMedia(){
    const hero=document.querySelector('#hero');
    const item=activeHeroItem();
    const token=++sequence;
    cleanupHeroMedia();
    if(!hero||!item){setCarouselTimer(ART_MS);return;}

    const media=await fetchHeroMedia(item);
    if(token!==sequence||document.querySelector('#hero')!==hero)return;
    installLogo(hero,item,media.logo);

    if(document.hidden||reducedMotion()||performanceMode()||saveData()||!media.trailer){
      setCarouselTimer(ART_MS);
      return;
    }

    const installed=installTrailer(hero,item,media.trailer,token);
    setCarouselTimer(installed?TRAILER_MS:ART_MS);
  }

  const baseBindHero=typeof bindHero==='function'?bindHero:null;
  if(baseBindHero){
    bindHero=function(){
      baseBindHero();
      window.setTimeout(loadHeroMedia,80);
    };
  }

  const baseRenderHome=typeof renderHome==='function'?renderHome:null;
  if(baseRenderHome){
    renderHome=async function(...args){
      const result=await baseRenderHome(...args);
      window.setTimeout(loadHeroMedia,100);
      return result;
    };
  }

  document.addEventListener('click',event=>{
    const button=event.target.closest?.('[data-hero-audio]');
    if(!button)return;
    event.preventDefault();
    event.stopPropagation();
    muted=!muted;
    if(muted){command('mute');button.textContent='🔇';button.setAttribute('aria-label','Turn trailer sound on');}
    else{command('unMute');command('setVolume',[65]);command('playVideo');button.textContent='🔊';button.setAttribute('aria-label','Mute trailer');}
  },true);

  document.addEventListener('visibilitychange',()=>{
    if(!currentFrame)return;
    command(document.hidden?'pauseVideo':'playVideo');
  });

  window.setTimeout(loadHeroMedia,500);
})();
