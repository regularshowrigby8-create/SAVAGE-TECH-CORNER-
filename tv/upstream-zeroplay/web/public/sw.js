'use strict';
const CACHE='zeroplay-web-2.2.2-top10a';
const SHELL=['/','/index.html','/style.css','/layouts.css','/enhancements.css','/zero22.css','/top10-today.css?v=20261016a','/zsflix-ui.css?v=20261014a','/singlebar.css?v=20261014d','/playback-sources.js','/web-native.js','/browser.js','/layouts.js','/app.js','/zero22.js','/top10-today.js?v=20261016a','/web-fixes.js','/infinite-scroll.js?v=20261013c','/web-enhancements.js','/web-polish-fixes.js','/web-router.js?v=20261014d','/hero-trailer.js?v=20261014d','/manifest.json','/assets/icon.png','/assets/tmdb-logo.svg'];
self.addEventListener('install',event=>{event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(SHELL)).then(()=>self.skipWaiting()));});
self.addEventListener('activate',event=>{event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>key.startsWith('zeroplay-web-')&&key!==CACHE).map(key=>caches.delete(key)))).then(()=>self.clients.claim()));});
self.addEventListener('fetch',event=>{
  const request=event.request;if(request.method!=='GET')return;
  const url=new URL(request.url);if(url.origin!==self.location.origin||url.pathname.startsWith('/api/'))return;
  if(request.mode==='navigate'){
    event.respondWith(fetch(request).then(response=>{const copy=response.clone();caches.open(CACHE).then(cache=>cache.put('/index.html',copy));return response;}).catch(()=>caches.match('/index.html')));return;
  }
  event.respondWith(caches.match(request).then(cached=>{const update=fetch(request).then(response=>{if(response.ok)caches.open(CACHE).then(cache=>cache.put(request,response.clone()));return response;}).catch(()=>cached);return cached||update;}));
});
