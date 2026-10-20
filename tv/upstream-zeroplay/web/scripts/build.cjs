const fs=require('node:fs'),path=require('node:path'),{execFileSync}=require('node:child_process');
const publicDir=path.join(__dirname,'../public');
const required=['index.html','app.js','browser.js','playback-sources.js','infinite-scroll.js','web-enhancements.js','web-polish-fixes.js','web-fixes.js','web-router.js','hero-trailer.js','style.css','layouts.css','enhancements.css','zero22.css','zsflix-ui.css','singlebar.css','layouts.js','manifest.json','sw.js','assets/icon.png','assets/tmdb-logo.svg'];
for(const file of required)if(!fs.existsSync(path.join(publicDir,file)))throw Error('Missing website file: '+file);
for(const file of ['app.js','browser.js','playback-sources.js','infinite-scroll.js','web-enhancements.js','web-polish-fixes.js','web-fixes.js','web-router.js','hero-trailer.js','layouts.js','sw.js'])execFileSync(process.execPath,['--check',path.join(publicDir,file)],{stdio:'pipe'});
const manifest=JSON.parse(fs.readFileSync(path.join(publicDir,'manifest.json'),'utf8'));if(manifest.display!=='standalone'||manifest.short_name!=='ZeroPlay')throw Error('Invalid ZeroPlay web manifest');
const html=fs.readFileSync(path.join(publicDir,'index.html'),'utf8');
for(const asset of ['/style.css','/app.js','/web-router.js','/zsflix-ui.css','/singlebar.css','/hero-trailer.js'])if(!html.includes(asset))throw Error('Website shell is not root-safe: '+asset);
console.log('ZeroPlay 2.2 website ready for deployment. Configure TMDB_API_KEY in the hosting platform environment.');
