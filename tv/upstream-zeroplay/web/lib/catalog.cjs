function endpoint(path){return typeof path==='string'&&(/^\/(trending\/all\/(day|week)|movie\/(popular|top_rated|now_playing|upcoming)|tv\/(popular|airing_today|on_the_air)|search\/(multi|person)|watch\/providers\/(movie|tv)|discover\/(movie|tv))$/.test(path)||/^\/(movie|tv)\/[1-9]\d{0,9}(\/season\/\d{1,4})?$/.test(path)||/^\/person\/[1-9]\d{0,9}$/.test(path)||/^\/collection\/[1-9]\d{0,9}$/.test(path));}
function query(params){
  if(!params||!endpoint(params.path))throw Error('Unsupported catalog route');
  const allowed=new Set(['query','page','append_to_response','include_image_language','watch_region','with_watch_providers','with_watch_monetization_types','sort_by','with_genres','with_original_language','primary_release_date.gte','primary_release_date.lte','first_air_date.gte','first_air_date.lte','vote_average.gte','vote_count.gte','with_runtime.lte','with_origin_country']);const out=new URLSearchParams({include_adult:'false'});
  for(const [name,value]of Object.entries(params)){
    if(name==='path')continue;if(!allowed.has(name)||typeof value!=='string'||value.length>200)throw Error('Unsupported parameter');
    if(name==='page'&&!/^[1-9]\d{0,2}$/.test(value)||name==='page'&&Number(value)>500)throw Error('Invalid page');
    if(name==='append_to_response'&&!['credits,videos,recommendations','credits,videos,recommendations,images','videos','videos,images','images','combined_credits'].includes(value))throw Error('Invalid details request');
    if(name==='include_image_language'&&!/^(?:[a-z]{2}|null)(?:,(?:[a-z]{2}|null)){0,4}$/.test(value))throw Error('Invalid image language');
    if(name==='watch_region'&&!['PH','US','GB','CA','AU','IN','JP'].includes(value))throw Error('Invalid region');
    if(name==='with_watch_providers'&&!/^[1-9]\d{0,7}$/.test(value))throw Error('Invalid provider');
    if(name==='with_watch_monetization_types'&&value!=='flatrate')throw Error('Invalid availability');
    if(name==='sort_by'&&value!=='popularity.desc')throw Error('Invalid sorting');
    if(name==='with_genres'&&!/^\d{1,8}$/.test(value))throw Error('Invalid genre');
    if(name==='with_original_language'&&!/^[a-z]{2}$/.test(value))throw Error('Invalid language');
    if((name==='primary_release_date.gte'||name==='primary_release_date.lte'||name==='first_air_date.gte'||name==='first_air_date.lte')&&!/^\d{4}-\d{2}-\d{2}$/.test(value))throw Error('Invalid date filter');
    if(name==='vote_average.gte'&&(!/^\d(?:\.\d)?$/.test(value)||Number(value)>10))throw Error('Invalid rating');
    if(name==='vote_count.gte'&&!/^\d{1,7}$/.test(value))throw Error('Invalid vote filter');
    if(name==='with_runtime.lte'&&(!/^\d{1,3}$/.test(value)||Number(value)>600))throw Error('Invalid runtime');
    if(name==='with_origin_country'&&!/^[A-Z]{2}$/.test(value))throw Error('Invalid country');
    out.set(name,value);
  }return out;
}
module.exports={endpoint,query};
