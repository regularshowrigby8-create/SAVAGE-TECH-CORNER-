const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");

const html = fs.readFileSync(path.join(__dirname, "..", "index.html"), "utf8");
const menu = html.match(/<aside class="menu-drawer"[\s\S]*?<\/aside>/)?.[0] || "";
const checks = [
  ["all primary routes are together in the single menu dropdown", ["home", "search", "movies", "series", "categories", "library", "sources", "downloads", "settings"].every(route => menu.includes(`data-menu-route="${route}"`))],
  ["persistent desktop rail is removed", !html.includes('id="desktopRail"') && !html.includes('class="zp-sidebar"')],
  ["persistent mobile navigation dock is removed", !html.includes('class="nav mobile-bottom-nav"') && !html.includes('id="btnMobileMore"')],
  ["competing header navigation and category strip are removed", !html.includes('<nav class="nav" aria-label="Primary">') && !html.includes('aria-label="Popular categories"')],
  ["one menu button opens the dropdown with the existing accessible state", html.includes('id="btnMenu"') && html.includes('aria-controls="menuDrawer"') && /function openMenuDrawer\(\)/.test(html)],
  ["dropdown remains dismissible by its close button and outside scrim", html.includes('id="btnCloseMenu"') && html.includes('id="menuScrim"') && html.includes('$("#menuScrim").onclick=closeMenuDrawer')],
  ["route rendering and active menu state remain in place", /function setNav\(name\)/.test(html) && /function render\(\)/.test(html)],
  ["native Android TV layout support remains", html.includes('body.android-tv .topbar-inner') && html.includes('ANDROID_TV_APP')],
];

for (const [name, passed] of checks) {
  assert.ok(passed, `FAIL ${name}`);
  console.log(`PASS ${name}`);
}
console.log(`\n${checks.length} single-menu shell checks passed`);
