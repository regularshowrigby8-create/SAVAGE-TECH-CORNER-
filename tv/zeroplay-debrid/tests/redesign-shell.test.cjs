const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");

const html = fs.readFileSync(path.join(__dirname, "..", "index.html"), "utf8");
const menu = html.match(/<aside class="menu-drawer"[\s\S]*?<\/aside>/)?.[0] || "";
const rail = html.match(/<aside class="zp-sidebar"[\s\S]*?<\/aside>/)?.[0] || "";
const dock = html.match(/<nav class="mobile-bottom-nav"[\s\S]*?<\/nav>/)?.[0] || "";
const checks = [
  ["the combined redesign keeps the established route list in the More menu", ["home", "search", "movies", "series", "categories", "library", "sources", "downloads", "settings"].every(route => menu.includes(`data-menu-route="${route}"`))],
  ["desktop browse rail is present and uses working route controls", !!rail && ["home", "search", "movies", "series", "categories", "library"].every(route => rail.includes(`data-menu-route="${route}"`))],
  ["optional tools remain out of the persistent browse rail", !/data-menu-route="(?:sources|downloads|settings)"/.test(rail) && !/M3U|Diagnostics|Watch party|Profiles/.test(rail)],
  ["small-screen navigation dock contains browse routes only", !!dock && ["home", "search", "movies", "series", "library"].every(route => dock.includes(`data-menu-route="${route}"`)) && !/data-menu-route="(?:sources|downloads|settings)"/.test(dock)],
  ["menu button and rail More button open the accessible drawer", html.includes('id="btnMenu"') && html.includes('id="zpSidebarMore"') && html.includes('aria-controls="menuDrawer"') && /function openMenuDrawer\(\)/.test(html)],
  ["dropdown remains dismissible by its close button and outside scrim", html.includes('id="btnCloseMenu"') && html.includes('id="menuScrim"') && html.includes('$("#menuScrim").onclick=closeMenuDrawer')],
  ["route rendering highlights desktop and small-screen navigation accessibly", /function setNav\(name\)/.test(html) && html.includes('b.setAttribute("aria-current","page")') && /function render\(\)/.test(html)],
  ["combined preview colors keep both green/cyan TV focus cues and violet branding", html.includes('--zp-electric:#00d09c') && html.includes('--zp-focus:#00e5ff') && html.includes('linear-gradient(135deg,#7c3aed,#5b31c6)')],
  ["native Android TV layout and remote support remain", html.includes('body.android-tv .topbar-inner') && html.includes('ANDROID_TV_APP') && html.includes('function moveTvFocus')],
];

for (const [name, passed] of checks) {
  assert.ok(passed, `FAIL ${name}`);
  console.log(`PASS ${name}`);
}
console.log(`\n${checks.length} combined redesign shell checks passed`);
