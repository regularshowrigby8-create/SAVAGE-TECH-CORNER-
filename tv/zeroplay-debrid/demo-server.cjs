/* Local preview harness for the overlay audit.
   Serves index.html and answers api/tmdb + api/extract + a sample feed with
   local fixtures, so a viewer can exercise every overlay without a network. */
const http=require('http'), fs=require('fs'), path=require('path');

const FEED_BASE='http://LOCAL/addon/example';
const CATALOGUE={results:[
  {id:550,media_type:'movie',title:'Fight Club',poster_path:'/pB8BM7pdSp6B6Ih7QZ4DrQ3PmJK.jpg',backdrop_path:'/fCayJrkfRaCRCTh8GqN30f8oyQF.jpg',vote_average:8.4,release_date:'1999-10-15',overview:'An insomniac office worker and a soap salesman build a support group for men.'},
  {id:1399,media_type:'tv',name:'Game of Thrones',poster_path:'/1XS1oqL89opfnbLl8WnZY1O1uJx.jpg',backdrop_path:'/2OMB0ynKlyIenMJWI2Dy9IWT4c.jpg',vote_average:8.4,first_air_date:'2011-04-17',overview:'Noble families vie for control of Westeros.'},
  {id:27205,media_type:'movie',title:'Inception',poster_path:'/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg',vote_average:8.4,release_date:'2010-07-15',overview:'A thief steals secrets through dream-sharing.'},
  {id:1396,media_type:'tv',name:'Breaking Bad',poster_path:'/3xnWaLQjelJDDF7LT1WBo6f4BRe.jpg',vote_average:8.9,first_air_date:'2008-01-20',overview:'A chemistry teacher turns to crime.'}
]};

const streamsFor=(type,id)=>({streams:[
  {url:'http://LOCAL/media/sample.mp4',name:'ZeroPlay',title:'1080p · direct file · 3.2 MB'},
  {url:'http://LOCAL/media/sample.m3u8',name:'ZeroPlay',title:'720p · HLS',behaviorHints:{}},
  {infoHash:'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',name:'Torrent only',title:'2160p · magnet'},
  {externalUrl:'https://example.com/watch',name:'External',title:'some other site'},
  {url:'http://LOCAL/media/sample.mp4#2',name:'ZeroPlay',title:'1080p · proxy blocked',behaviorHints:{proxyHeaders:{request:{Referer:'x'}}}},
  {url:'http://LOCAL/media/sample.mp4#3',name:'ZeroPlay',title:'1080p · not web ready',behaviorHints:{notWebReady:true}},
]});

function json(res,body,status){
  res.writeHead(status||200,{'content-type':'application/json','access-control-allow-origin':'*'});
  res.end(JSON.stringify(body));
}

const server=http.createServer((req,res)=>{
  const url=new URL(req.url,'http://localhost');
  let p=decodeURIComponent(url.pathname);
  if(p==='/') p='/index.html';
  if(p==='/addon/example/manifest.json'){
    return json(res,{id:'org.zeroplay.example',version:'1.0.0',name:'ZeroPlay Sample Feed',
      description:'Local sample addon for the overlay audit.',types:['movie','series'],
      resources:['stream'],idPrefixes:['tt'],catalogs:[]});
  }
  const m=p.match(/^\/addon\/example\/stream\/(movie|series)\/([^/]+)\.json$/);
  if(m) return json(res,streamsFor(m[1],decodeURIComponent(m[2])));
  if(p==='/api/tmdb'||p==='/api/extract') return json(res,{ok:false,error:'not needed in demo'},404);
  if(p==='/media/sample.m3u8'){
    res.writeHead(200,{'content-type':'application/vnd.apple.mpegurl'});
    return res.end('#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:4,\nseg0.ts\n#EXT-X-ENDLIST\n');
  }
  if(p==='/local/catalogue.json') return json(res,CATALOGUE);
  const f=path.join(process.cwd(),p);
  if(fs.existsSync(f)&&fs.statSync(f).isFile()){
    const ext=path.extname(f);
    const type={'.html':'text/html','.js':'text/javascript','.css':'text/css','.png':'image/png','.mp4':'video/mp4'}[ext]||'application/octet-stream';
    res.writeHead(200,{'content-type':type});
    return fs.createReadStream(f).pipe(res);
  }
  res.writeHead(404,{'content-type':'text/plain'});
  res.end('not found');
});
const port=Number(process.env.PORT||8099);
server.listen(port,'0.0.0.0',()=>console.log('demo server on http://0.0.0.0:'+port));
