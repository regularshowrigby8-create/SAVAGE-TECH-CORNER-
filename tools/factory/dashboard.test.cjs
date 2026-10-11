/** Exercise actual generated dashboard handlers, not just element existence. */
const fs = require("node:fs");
const assert = require("node:assert/strict");
const { JSDOM } = require("jsdom");
const dom = new JSDOM(
  fs.readFileSync(process.argv[2] || "docs/factory/FACTORY.html", "utf8"),
  { runScripts: "dangerously", url: "https://factory.invalid" },
);
const doc = dom.window.document;
const search = doc.querySelector("#search");
const state = doc.querySelector("#state");
const visible = () =>
  [...doc.querySelectorAll("tbody tr")].filter((row) => !row.hidden);
assert.equal(visible().length, 66);
search.value = "pipdeptree";
search.dispatchEvent(new dom.window.Event("input"));
assert.equal(visible().length, 1);
search.value = "no-such-engine";
search.dispatchEvent(new dom.window.Event("input"));
assert.equal(visible().length, 0);
doc.querySelector("#reset").click();
assert.equal(visible().length, 66);
state.value = "FAIL";
state.dispatchEvent(new dom.window.Event("change"));
const failures = [...doc.querySelectorAll("tbody tr")].filter(
  (row) => row.dataset.state === "FAIL",
);
assert.ok(failures.length > 0);
assert.equal(visible().length, failures.length);
assert.ok(visible().every((row) => row.dataset.state === "FAIL"));
doc.querySelector("#reset").click();
assert.equal(visible().length, 66);
assert.equal(doc.activeElement, search);
assert.equal(doc.querySelector("#count").textContent, "64 of 64 tools shown");
dom.window.close();
console.log(
  "Dashboard search/no-match/status/reset/focus/count behavior: PASS (jsdom, not native WebView)",
);
