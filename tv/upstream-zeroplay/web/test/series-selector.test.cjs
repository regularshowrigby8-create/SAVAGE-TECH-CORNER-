const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'..','public','app.js'),'utf8');
const browser=fs.readFileSync(path.join(__dirname,'..','public','browser.js'),'utf8');
test('series details keep season and episode selectors separate from Watch Now',()=>{
  assert.match(source,/class=\\?"episode-picker/);
  assert.match(source,/id=\\?"season/);
  assert.match(source,/id=\\?"episode/);
  assert.doesNotMatch(source,/data-action=\\?"play-episode/);
});
test('Watch Now uses the selected season and episode for TV details',()=>{
  assert.match(source,/item\?\.type==='tv'/);
  assert.match(source,/Number\(\$\('#season'\)\?\.value\)/);
  assert.match(source,/Number\(\$\('#episode'\)\?\.value\)/);
  assert.match(source,/ep=\{season,episode\}/);
});
test('website details reopen on the last played season and episode',()=>{
  assert.match(source,/const resume=state\.positions\[key\(item\)\]/);
  assert.match(source,/resume\?\.season/);
  assert.match(source,/await loadEpisodes\(item,token,resume\?\.episode\)/);
  assert.match(browser,/let position=selected\|\|saved\.positions\[key\(item\)\]\|\|\{\}/);
  assert.match(browser,/saved\.positions\[key\(item\)\]=\{\.\.\.row\}/);
});
test('website marks a title started before provider progress arrives',()=>{
  assert.match(browser,/if\(!\(Number\(row\.timestamp\)>0\)&&!position\.duration\)row=\{\.\.\.row,timestamp:1,duration:0,percent:0\}/);
});
