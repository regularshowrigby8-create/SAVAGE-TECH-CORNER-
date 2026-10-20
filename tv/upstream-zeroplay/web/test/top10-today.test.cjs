const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'../..');
const read=file=>fs.readFileSync(path.join(root,file),'utf8');

test('website Top 10 Today is loaded from the daily trend and opens details',()=>{
  const html=read('web/public/index.html');
  const js=read('web/public/top10-today.js');
  const css=read('web/public/top10-today.css');
  const sw=read('web/public/sw.js');
  const catalog=require(path.join(root,'web/lib/catalog.cjs'));
  assert.match(html,/top10-today\.css\?v=20261016a/);
  assert.match(html,/top10-today\.js\?v=20261016a/);
  assert.match(js,/\/trending\/all\/day/);
  assert.match(js,/slice\(0,10\)/);
  assert.match(js,/data-detail=/);
  assert.match(js,/titles\.set\(key\(item\),item\)/);
  assert.match(js,/TOP 10 TODAY/);
  assert.match(css,/\.zero-web-top10-rank/);
  assert.match(css,/\.zero-web-top10-poster/);
  assert.match(sw,/zeroplay-web-2\.2\.2-top10a/);
  assert.match(sw,/top10-today\.js\?v=20261016a/);
  assert.equal(catalog.endpoint('/trending/all/day'),true);
  assert.equal(catalog.endpoint('/trending/all/week'),true);
});
