const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");

const html = fs.readFileSync(path.join(__dirname, "..", "index.html"), "utf8");
const checks = [
  ["dark is the default theme", html.includes('theme:"dark"')],
  ["theme choices include OLED and warm", html.includes('value="oled"') && html.includes('value="warm"')],
  ["text scaling offers standard, large, and extra large", ["normal", "large", "xl"].every(value => html.includes(`value="${value}"`))],
  ["high contrast preference is exposed accessibly", html.includes('id="uiHighContrast" data-ui-preference="highContrast"')],
  ["theme and text scale classes are applied", ["theme-oled", "theme-warm", "font-large", "font-xl"].every(name => html.includes(`classList.toggle(\"${name}\"`))],
  ["preference changes persist and reapply the interface", html.includes('closest("[data-ui-preference]")') && html.includes("saveSettings(); applyInterfaceSettings();")],
  ["unrecognized themes and text scales fall back safely", html.includes('["dark","oled","warm"].includes(ui.theme)') && html.includes('["normal","large","xl"].includes(ui.textScale)')],
  ["display preference theme styles are present", ["body.theme-oled", "body.theme-warm", "body.high-contrast"].every(name => html.includes(name))],
];

for (const [name, passed] of checks) {
  assert.ok(passed, `FAIL ${name}`);
  console.log(`PASS ${name}`);
}
console.log(`\n${checks.length} accessibility/display preference checks passed`);
