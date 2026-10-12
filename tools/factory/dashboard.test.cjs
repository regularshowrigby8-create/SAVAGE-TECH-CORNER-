/** Exercise actual generated dashboard handlers, not just element existence.
 *  Expectations are derived from the committed tool registry (tools.json),
 *  never from magic constants: if the registry and the rendered dashboard
 *  drift apart, this test fails. */
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const { JSDOM } = require("jsdom");

const FACTORY_DIR = __dirname;
const registry = JSON.parse(fs.readFileSync(path.join(FACTORY_DIR, "tools.json"), "utf8"));
const expectedTools = registry.tools.length;
assert.ok(expectedTools > 0, "tool registry must not be empty");

const htmlPath =
  process.argv[2] || path.join(FACTORY_DIR, "..", "..", "docs", "factory", "FACTORY.html");
const dom = new JSDOM(fs.readFileSync(htmlPath, "utf8"), {
  runScripts: "dangerously",
  url: "https://factory.invalid",
});
const doc = dom.window.document;
const search = doc.querySelector("#search");
const state = doc.querySelector("#state");
assert.ok(search, "dashboard must render a search input");
assert.ok(state, "dashboard must render a state filter");
const visible = () => [...doc.querySelectorAll("tbody tr")].filter((row) => !row.hidden);

// Registry/dashboard drift guard: one rendered row per registered tool.
assert.equal(
  doc.querySelectorAll("tbody tr").length,
  expectedTools,
  `rendered row count must equal tools.json registry size (${expectedTools}); regenerate via: python3 -m tools.factory.automation overview --publish-doc`,
);
assert.equal(visible().length, expectedTools);

// Search narrows to exactly the matching engine.
search.value = "pipdeptree";
search.dispatchEvent(new dom.window.Event("input"));
assert.equal(visible().length, 1);
search.value = "no-such-engine";
search.dispatchEvent(new dom.window.Event("input"));
assert.equal(visible().length, 0);
doc.querySelector("#reset").click();
assert.equal(visible().length, expectedTools);

// State filter is strict: with an all-UNVERIFIED baseline, filtering FAIL
// must show exactly the FAIL rows (zero today — never a fabricated pass).
state.value = "FAIL";
state.dispatchEvent(new dom.window.Event("change"));
const failures = [...doc.querySelectorAll("tbody tr")].filter(
  (row) => row.dataset.state === "FAIL",
);
assert.equal(visible().length, failures.length);
assert.ok(visible().every((row) => row.dataset.state === "FAIL"));
state.value = "UNVERIFIED";
state.dispatchEvent(new dom.window.Event("change"));
assert.ok(
  visible().every((row) => row.dataset.state === "UNVERIFIED"),
  "UNVERIFIED filter must show only UNVERIFIED rows",
);
doc.querySelector("#reset").click();
assert.equal(visible().length, expectedTools);
assert.equal(doc.activeElement, search, "reset must return focus to search");
assert.equal(
  doc.querySelector("#count").textContent,
  `${expectedTools} of ${expectedTools} tools shown`,
);
dom.window.close();
console.log(
  `Dashboard registry/search/no-match/status/reset/focus/count behavior: PASS (${expectedTools} engines verified, jsdom)`,
);
