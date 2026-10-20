(function(root,factory){if(typeof module==='object'&&module.exports)module.exports=factory();else root.playbackSources=factory();})(typeof globalThis==='object'?globalThis:this,()=>{
  const sources=[{id:'vidstuck',name:'VidStuck'},{id:'zxcstream',name:'ZXCStream (Beta)'},{id:'embedwave',name:'Embedwave (BETA)'}];
  const hosts=['vidstuck.cc','zxcstream.cc','embedwave.cc'];
  function source(id){return sources.find(row=>row.id===id)||sources[0];}
  function trusted(value){try{const u=new URL(value);return u.protocol==='https:'&&!u.port&&hosts.includes(u.hostname);}catch{return false;}}
  function url(item,position={},provider='vidstuck'){
    if(!['movie','tv'].includes(item?.type)||!Number.isSafeInteger(item.id)||item.id<1)throw Error('Invalid title');
    const season=Number.isInteger(position.season)&&position.season>=0?position.season:1,episode=Number.isInteger(position.episode)&&position.episode>0?position.episode:1;
    const suffix=`${item.type}/${item.id}`+(item.type==='tv'?`/${season}/${episode}`:'');const id=source(provider).id;
    const host=id==='embedwave'?'embedwave.cc':id==='zxcstream'?'zxcstream.cc':'vidstuck.cc';const u=id==='zxcstream'?new URL(`https://${host}/player/${suffix}`):new URL(`https://${host}/embed/${suffix}`);if(id==='zxcstream'){u.searchParams.set('domainAd','zeroplay');u.searchParams.set('color','65E6CC');u.searchParams.set('autoplay','true');}
    if(id==='vidstuck'){
      for(const [k,v]of Object.entries({branding:'ZeroPlay',color:'65E6CC',subtitle:'english',overlay:'true'}))u.searchParams.set(k,v);
      if(item.type==='tv')for(const k of ['nextEpisode','episodeSelector','autoplayNextEpisode'])u.searchParams.set(k,'true');
      if(Number.isFinite(position.timestamp)&&position.timestamp>30&&Number(position.percent)<95)u.searchParams.set('progress',String(Math.floor(position.timestamp)));
    }
    return u.href;
  }
  function normalize(data){if(data?.type!=='PLAYER_EVENT')return data;const p=data.data,info=p?.player_info;if(!info?.tmdb)return null;return {id:info.tmdb,type:info.mediaType,timestamp:p.player_progress,duration:p.player_duration,season:info.season,episode:info.episode};}
  return {sources,source,trusted,url,normalize};
});
