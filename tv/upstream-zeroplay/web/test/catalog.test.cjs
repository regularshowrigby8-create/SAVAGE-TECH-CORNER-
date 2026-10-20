const test=require('node:test'),assert=require('node:assert/strict');
const {endpoint,query}=require('../lib/catalog.cjs'),{handler}=require('../functions/tmdb.js');
test('TMDB route and query validation cannot become an arbitrary proxy',()=>{
  assert.equal(query({path:'/search/multi',query:'My own movie',page:'2'}).get('query'),'My own movie');assert.equal(query({path:'/tv/1399/season/0'}).get('include_adult'),'false');
  for(const params of [{path:'https://attacker.example'},{path:'/movie/../configuration'},{path:'/movie/299534',api_key:'replacement'},{path:'/search/multi',include_adult:'true'},{path:'/movie/popular',page:'501'},{path:'/movie/popular',page:'-1'},{path:'/watch/providers/movie',watch_region:'../US'},{path:'/movie/299534',append_to_response:'account'}])assert.throws(()=>query(params));
});
test('2.2 discovery routes and filters stay bounded to approved TMDB inputs',()=>{
  for(const path of ['/tv/airing_today','/tv/on_the_air','/search/person','/person/287','/collection/86311','/discover/movie','/discover/tv'])assert.equal(endpoint(path),true,path);
  for(const path of ['/search/company','/person/0','/collection/../1','/discover/person'])assert.equal(endpoint(path),false,path);
  const movie=query({path:'/discover/movie',page:'3',with_genres:'878',with_original_language:'en','primary_release_date.gte':'2025-01-01','primary_release_date.lte':'2026-10-13','vote_average.gte':'7.5','vote_count.gte':'250','with_runtime.lte':'120',with_origin_country:'US',sort_by:'popularity.desc'});
  assert.equal(movie.get('with_genres'),'878');assert.equal(movie.get('vote_average.gte'),'7.5');assert.equal(movie.get('with_runtime.lte'),'120');assert.equal(movie.get('with_origin_country'),'US');
  assert.equal(query({path:'/person/287',append_to_response:'combined_credits'}).get('append_to_response'),'combined_credits');
  for(const params of [{path:'/discover/movie',with_genres:'28|35'},{path:'/discover/movie',with_original_language:'EN'},{path:'/discover/movie','vote_average.gte':'11'},{path:'/discover/movie','with_runtime.lte':'601'},{path:'/discover/movie',with_origin_country:'usa'},{path:'/discover/movie','primary_release_date.gte':'2026/01/01'}])assert.throws(()=>query(params));
});
test('Function enforces GET, handles missing key, and does not return upstream secrets',async()=>{
  const oldKey=process.env.TMDB_API_KEY,oldFetch=global.fetch;
  try{
    delete process.env.TMDB_API_KEY;assert.equal((await handler({httpMethod:'POST'})).statusCode,405);
    assert.equal((await handler({httpMethod:'GET',queryStringParameters:{path:'https://attacker.example'}})).statusCode,400);
    assert.equal((await handler({httpMethod:'GET',queryStringParameters:{path:'/movie/popular'}})).statusCode,503);
    process.env.TMDB_API_KEY='test-server-secret';let requested;
    global.fetch=async(url)=>{requested=new URL(url);return {ok:true,json:async()=>({results:[{id:299534,title:'A movie'}]})};};
    const response=await handler({httpMethod:'GET',queryStringParameters:{path:'/search/multi',query:'A movie',page:'1'}});
    assert.equal(response.statusCode,200);assert.equal(requested.hostname,'api.themoviedb.org');assert.equal(requested.searchParams.get('api_key'),'test-server-secret');assert.ok(!response.body.includes('test-server-secret'));assert.equal(response.headers['Cache-Control'],'private, max-age=60');
    global.fetch=async()=>({ok:false,status:401,json:async()=>({error:'test-server-secret'})});const rejected=await handler({httpMethod:'GET',queryStringParameters:{path:'/movie/popular'}});assert.equal(rejected.statusCode,502);assert.ok(!rejected.body.includes('test-server-secret'));
    global.fetch=async()=>{throw Error('test-server-secret');};assert.ok(!(await handler({httpMethod:'GET',queryStringParameters:{path:'/movie/popular'}})).body.includes('test-server-secret'));
  }finally{global.fetch=oldFetch;if(oldKey===undefined)delete process.env.TMDB_API_KEY;else process.env.TMDB_API_KEY=oldKey;}
});
