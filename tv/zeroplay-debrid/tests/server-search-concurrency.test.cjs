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
  enabledAddons() { return ctx.settings.addons; },
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
    onProgress(snapshot) { updates.push(snapshot.queried); },
  });
  assert.strictEqual(maxActive, 3, 'uses configured provider concurrency');
  assert.strictEqual(result.concurrency, 3, 'reports actual concurrency');
  assert.strictEqual(result.queried, addons.length, 'queries every enabled provider');
  assert.strictEqual(result.direct.length, addons.length, 'preserves every verified matching stream');
  assert.strictEqual(updates.length, addons.length, 'reports progress per provider, not per completed batch');
  assert.strictEqual(updates[0], 1, 'first provider completion is emitted immediately');
  assert.notStrictEqual(completed[0], 'provider-0', 'fast providers are not held behind a slow provider');
  assert.strictEqual(result.identityVerified, addons.length, 'aggregates verified stream counts');

  const allProviders = Array.from({ length: 120 }, (_, i) => ({ id: `vod-${i}`, name: `VOD ${i}` }));
  ctx.settings.addons = allProviders;
  let started = 0;
  ctx.queryAddonStreams = async addon => {
    started++;
    await new Promise(resolve => setTimeout(resolve, 1));
    return { direct: [{ url: `https://cdn.test/${addon.id}.mp4`, addonName: addon.name, addonBase: `https://${addon.id}.test` }] };
  };
  const fullScan = await ctx.collectAllStreams({ title: 'Series', type: 'tv' }, {});
  assert.strictEqual(fullScan.concurrency, 100, 'uses the requested 100-worker default for a large VOD provider list');
  assert.strictEqual(started, 120, 'does not stop provider scheduling after finding matching servers');
  assert.strictEqual(fullScan.queried, 120, 'scans the complete enabled VOD provider list');
  assert.strictEqual(fullScan.totalAddons, 120, 'reports the full provider inventory');
  assert.strictEqual(fullScan.direct.length, 120, 'does not cap verified direct results');
  console.log('PASS bounded concurrent workers and immediate per-provider progress');
  console.log('PASS uncapped VOD provider scan returns all matching servers');
})().catch(error => { console.error(error); process.exit(1); });
