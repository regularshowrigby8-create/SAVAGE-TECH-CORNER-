const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const path = require('path');

const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
const script = html.slice(html.indexOf('<script>') + 8, html.lastIndexOf('</script>'));
const start = script.indexOf('async function collectAllStreams(ctx,opts)');
const end = script.indexOf('\nasync function measureSpeed', start);
assert(start >= 0 && end > start, 'collectAllStreams must exist');

const addons = Array.from({ length: 7 }, (_, i) => ({ id: `provider-${i}`, name: `Provider ${i}` }));
let active = 0;
let maxActive = 0;
const completed = [];
const updates = [];
const ctx = {
  settings: { stream: { quality: 'auto' }, addons },
  enabledAddons() { return addons; },
  healthScore() { return 50; },
  normalizeQualityLabel(value) { return value; },
  qualityHeight(value) { return Number.parseInt(value, 10) || 0; },
  buildTitleIdentity(input) { return { version: 1, title: input.title || 'Series', type: 'tv' }; },
  async queryAddonStreams(addon) {
    active++;
    maxActive = Math.max(maxActive, active);
    const delays = { 'provider-0': 35, 'provider-1': 5, 'provider-2': 15, 'provider-3': 3, 'provider-4': 8, 'provider-5': 1, 'provider-6': 2 };
    await new Promise(resolve => setTimeout(resolve, delays[addon.id]));
    active--;
    completed.push(addon.id);
    return {
      direct: [{ url: `https://cdn.test/${addon.id}.mp4`, addonName: addon.name, addonBase: `https://${addon.id}.test`, quality: '1080p', identityScore: 99 }],
      identityVerified: 1,
    };
  },
};
ctx.globalThis = ctx;
vm.createContext(ctx);
vm.runInContext(script.slice(start, end), ctx, { filename: 'collect-all-streams.js' });

(async () => {
  const result = await ctx.collectAllStreams({ title: 'Series', type: 'tv' }, {
    concurrency: 3,
    stopAfterServers: 0,
    onProgress(snapshot) { updates.push(snapshot.queried); },
  });
  assert.strictEqual(maxActive, 3, 'uses configured provider concurrency');
  assert.strictEqual(result.concurrency, 3, 'reports actual concurrency');
  assert.strictEqual(result.queried, addons.length, 'all providers are queried when no early stop is configured');
  assert.strictEqual(result.direct.length, addons.length, 'all verified matching streams are preserved');
  assert.strictEqual(updates.length, addons.length, 'reports progress per provider, not per completed batch');
  assert.strictEqual(updates[0], 1, 'first provider completion is emitted immediately');
  assert.notStrictEqual(completed[0], 'provider-0', 'fast providers are not held behind a slow provider in the first batch');
  assert.strictEqual(result.identityVerified, addons.length, 'aggregates verified stream counts');

  let started = 0;
  ctx.enabledAddons = () => addons;
  ctx.queryAddonStreams = async addon => {
    started++;
    await new Promise(resolve => setTimeout(resolve, 4));
    return { direct: [{ url: `https://cdn.test/limited-${addon.id}`, addonName: addon.name, addonBase: `https://${addon.id}.test` }] };
  };
  const limited = await ctx.collectAllStreams({ title: 'Series', type: 'tv' }, { concurrency: 3, stopAfterServers: 2 });
  assert(started >= 2 && started <= 4, 'stops scheduling once enough distinct matching servers have returned');
  assert(limited.searchLimited, 'marks an early-completed provider scan');
  assert(limited.direct.length >= 2, 'retains the matching servers found before stopping');
  console.log('PASS bounded server search concurrency and immediate provider progress');
  console.log('PASS early stopping after enough distinct server matches');
})().catch(error => { console.error(error); process.exit(1); });
