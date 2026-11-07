const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");

const html = fs.readFileSync(path.join(__dirname, "..", "index.html"), "utf8");
const checks = [
  ["desktop rail includes the existing Home, Movies, Series and Categories routes", /id="desktopRail"[\s\S]*?data-nav="home"[\s\S]*?data-nav="movies"[\s\S]*?data-nav="series"[\s\S]*?data-nav="categories"/.test(html)],
  ["desktop rail keeps Library, Downloads, Sources and Settings reachable", /data-nav="library"/.test(html) && /data-menu-route="downloads"/.test(html) && /data-nav="sources"/.test(html) && /data-menu-route="settings"/.test(html)],
  ["genre shortcuts use the existing category route data", /zp-sidebar-genres[\s\S]*?data-category-type="movie" data-category-id="28"/.test(html)],
  ["mobile dock provides Home, Movies, Series, Library and More", /mobile-bottom-nav[\s\S]*?data-nav="home"[\s\S]*?data-nav="movies"[\s\S]*?data-nav="series"[\s\S]*?data-nav="library"[\s\S]*?btnMobileMore/.test(html)],
  ["More opens the existing menu drawer", /mobileMoreButton\.onclick=openMenuDrawer/.test(html)],
  ["desktop sidebar activates only at wide layouts", /@media\(min-width:980px\)[\s\S]*?\.zp-sidebar\{position:fixed/.test(html)],
  ["mobile dock activates at narrow widths", /@media\(max-width:620px\)[\s\S]*?\.mobile-bottom-nav\{position:fixed/.test(html)],
  ["native Android TV keeps its existing navigation shell", /body\.android-tv \.topbar-inner/.test(html) && /body:not\(\.android-tv\) \.zp-sidebar/.test(html)],
  ["current route renderer and navigation state remain in place", /function setNav\(name\)/.test(html) && /function render\(\)/.test(html)],
];

for (const [name, passed] of checks) {
  assert.ok(passed, `FAIL ${name}`);
  console.log(`PASS ${name}`);
}
console.log(`\n${checks.length} redesigned-shell checks passed`);
