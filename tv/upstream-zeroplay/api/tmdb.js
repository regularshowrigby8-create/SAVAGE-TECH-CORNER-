const {query}=require('../web/lib/catalog.cjs');

const baseHeaders={
  'Content-Type':'application/json; charset=utf-8',
  'X-Content-Type-Options':'nosniff'
};

function send(res,status,body,extra={}){
  for(const [name,value] of Object.entries({...baseHeaders,...extra}))res.setHeader(name,value);
  return res.status(status).json(body);
}

module.exports=async function handler(req,res){
  if(req.method!=='GET'){
    res.setHeader('Allow','GET');
    return send(res,405,{error:'Method not allowed'});
  }

  const raw={};
  for(const [name,value] of Object.entries(req.query||{})){
    if(Array.isArray(value))return send(res,400,{error:'Invalid catalog request'});
    raw[name]=String(value);
  }

  let params;
  try{params=query(raw);}catch{return send(res,400,{error:'Invalid catalog request'});}

  const key=process.env.TMDB_API_KEY;
  if(!key)return send(res,503,{error:'The catalog is temporarily unavailable. Please try again later.'});

  params.set('api_key',key);
  const path=raw.path;
  const url=new URL('https://api.themoviedb.org/3'+path);
  url.search=params.toString();

  try{
    const response=await fetch(url,{
      signal:AbortSignal.timeout(15000),
      headers:{Accept:'application/json'}
    });
    if(!response.ok){
      return send(
        res,
        response.status===429?429:502,
        {error:'The catalog is temporarily unavailable. Please try again later.'},
        response.status===429?{'Retry-After':'30'}:{}
      );
    }
    const data=await response.json();
    return send(res,200,data,{
      'Cache-Control':path==='/search/multi'?'private, max-age=60':'public, max-age=300, s-maxage=300'
    });
  }catch{
    return send(res,502,{error:'The catalog is temporarily unavailable. Please try again later.'});
  }
};
