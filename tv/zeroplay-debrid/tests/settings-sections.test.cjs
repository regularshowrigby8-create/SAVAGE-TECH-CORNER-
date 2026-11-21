#!/usr/bin/env node
/* Settings section contract.
 *
 * Phase 2 replaced the "hide whole settings blocks on Android TV" rule with
 * collapsible sections behind a sticky strip. This suite pins the three things
 * that can quietly break that: a section that exists in the strip but not on
 * the page (a dead tab), a settings control that lost its panel, and a strip
 * that stops being a D-pad row of its own.
 */
const fs = require("fs");
const path = require("path");
const assert = require("assert");

const html = fs.readFileSync(path.join(__dirname, "..", "index.html"), "utf8");
let passed = 0;
const ok = (condition, label) => {
  assert.ok(condition, label);
  passed += 1;
  console.log("PASS " + label);
};

const GROUPS = ["everyday", "playback", "sources", "addons", "downloads", "data"];

/* 1. Every tab in the strip has a real panel on the page. */
const tabGroups = [...html.matchAll(/data-panel-tab="([a-z]+)"/g)].map((m) => m[1]);
ok(tabGroups.length === GROUPS.length, "the strip renders one tab per section");
for (const group of GROUPS) {
  ok(tabGroups.includes(group), "strip has a " + group + " tab");
  const panel = new RegExp(`<section class="panel" id="[A-Za-z]+" data-panel-group="${group}"`);
  ok(panel.test(html), "a real panel is grouped under " + group);
}

/* 2. Nothing is deleted from the Android TV build any more. */
const tvHideRules = [...html.matchAll(/body\.android-tv[^{}]*\{[^}]*display:none!important[^}]*\}/g)].map((m) => m[0]);
ok(!tvHideRules.some((rule) => /\.panel|\.setting-switch|\.settings-hub-grid|#settingsHub/.test(rule)),
   "no Android TV rule deletes a settings panel or a switch");
ok(html.includes("is-collapsed"), "sections collapse rather than disappear");
ok(/\.panel\.is-collapsed > :not\(h2\) \{ display: none; \}/.test(html),
   "a collapsed section keeps its heading, so its name stays on screen");
ok(!html.includes("panel.hidden=!wanted.includes"), "the old hide-list is gone");

/* 3. The strip is itself a D-pad row, and activating a tab moves focus. */
ok(html.includes('data-tv-row="settings-tabs"'), "the settings strip is an explicit focus row");
ok(html.includes("function activateSettingsGroup("), "tabs have one activation path");
const activateBody = html.slice(html.indexOf("function activateSettingsGroup("), html.indexOf("function activateSettingsGroup(") + 900);
ok(/const first=active\.querySelector\(/.test(activateBody) && /tvFocusAndReveal\(first\)/.test(activateBody),
   "activating a section lands focus on its first control");

/* 4. Section state survives a soft re-render. */
ok(/sourcesRenderMode=Object\.assign\(\{\},sourcesRenderMode,\{settingsPage:/.test(html),
   "a soft re-render keeps the active section");

/* 5. The everyday hub is everyday-only; the other jobs moved to their own panels. */
const hub = html.slice(html.indexOf('id="settingsHub"'), html.indexOf('id="settingsSourcesHub"'));
ok(!/<h3>Downloads<\/h3>/.test(hub), "downloads switches are not buried in the everyday column");
ok(html.includes('id="settingsDownloadsHub"'), "downloads has its own section");
ok(html.includes('id="settingsDevice"'), "This TV and artwork have their own section");
ok(html.includes('id="settingsSourcesHub"'), "sources switches have their own section");

/* 6. A panel extracted out of settingsHubHTML() must carry its own locals.
 *    This is the bug that shipped once: the This TV panel used deviceText, which
 *    only existed inside settingsHubHTML(), so render threw and the settings
 *    route silently fell back to Sources. */
const hubStart = html.indexOf("function settingsHubHTML(){");
const hubBody = html.slice(hubStart, html.indexOf("function settingsSourcesSectionHTML(", hubStart));
const hubLocals = [...hubBody.matchAll(/^  const ([A-Za-z_$][\w$]*)[= ]/gm)].map((m) => m[1]);
ok(hubLocals.length > 0, "the everyday hub declares the locals the extracted panels used to share");
for (const fn of ["settingsSourcesSectionHTML", "settingsDownloadsSectionHTML", "settingsDeviceHTML"]) {
  const start = html.indexOf("function " + fn + "(){");
  const body = html.slice(start, html.indexOf("\nfunction ", start + 10));
  const declaredHere = [...body.matchAll(/^  const ([A-Za-z_$][\w$]*)[= ]/gm)].map((m) => m[1]);
  const leaked = hubLocals.filter((name) => declaredHere.indexOf(name) === -1 && new RegExp("\\b" + name + "\\b").test(body));
  ok(leaked.length === 0, fn + " declares everything it uses (leaked: " + (leaked.join(", ") || "none") + ")");
}

/* 7. Settings render must not be able to fall back to the Sources page. */
ok(/\+sourceStatusPanelHTML\(\)/.test(html), "the settings page still renders its status panel");
ok(html.includes("settingsPage?settingsSourcesSectionHTML()+settingsDownloadsSectionHTML()+settingsDeviceHTML()"),
   "all extracted panels are mounted on the settings page");

console.log(`\n${passed} settings section checks passed\n`);
