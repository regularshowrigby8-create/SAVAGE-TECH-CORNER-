const {query}=require('../lib/catalog.cjs');
const headers={'Content-Type':'application/json; charset=utf-8','X-Content-Type-Options':'nosniff'};
const respond=(statusCode,body,extra={})=>({statusCode,headers:{...headers,...extra},body:JSON.stringify(body)});
exports.handler=async event=>{
  if(event.httpMethod!=='GET')return respond(405,{error:'Method not allowed'},{Allow:'GET'});
  let params;try{params=query(event.queryStringParameters||{});}catch{return respond(400,{error:'Invalid catalog request'});}
  const key=process.env.TMDB_API_KEY;if(!key)return respond(503,{error:'The catalog is temporarily unavailable. Please try again later.'});
  params.set('api_key',key);const path=event.queryStringParameters.path,url=new URL('https://api.themoviedb.org/3'+path);url.search=params.toString();
  try{
    const response=await fetch(url,{signal:AbortSignal.timeout(15000),headers:{Accept:'application/json'}});
    if(!response.ok)return respond(response.status===429?429:502,{error:'The catalog is temporarily unavailable. Please try again later.'},response.status===429?{'Retry-After':'30'}:{});
    const data=await response.json();return respond(200,data,{'Cache-Control':path==='/search/multi'?'private, max-age=60':'public, max-age=300, s-maxage=300'});
  }catch{return respond(502,{error:'The catalog is temporarily unavailable. Please try again later.'});}
};
