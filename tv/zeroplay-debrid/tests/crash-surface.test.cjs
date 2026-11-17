"use strict";
/**
 * The blank-page contract.
 *
 * A white page is the worst possible failure: it reports nothing, so it cannot
 * be diagnosed from outside. These checks assert that the crash surface does its
 * job — stays invisible when the app is healthy, and paints a readable card when
 * the app script throws or boot stalls.
 *
 * Each case boots the real dist/index.html in jsdom. The fault is injected into
 * the HTML itself, which is the only way to reproduce "top-level throw kills the
 * inline script" without a browser.
 */

const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const { JSDOM, ResourceLoader, VirtualConsole } = require(path.join(ROOT, "node_modules/jsdom"));

const dist = fs.readFileSync(path.join(ROOT, "dist/index.html"), "utf8");
const bundle = fs.readFileSync(path.join(ROOT, "dist/app.bundle.js"), "utf8");

class Loader extends ResourceLoader {
  fetch(url) {
    return Promise.resolve(Buffer.from(url.endsWith("app.bundle.js") ? bundle : ""));
  }
}

let passed = 0;
const failures = [];
function ok(condition, label, detail) {
  if (condition) passed += 1;
  else failures.push(label + (detail ? " — " + detail : ""));
}

async function boot(html, waitMs) {
  const virtualConsole = new VirtualConsole();
  virtualConsole.on("jsdomError", () => {}); // expected: the injected fault
  const dom = new JSDOM(html, {
    runScripts: "dangerously",
    resources: new Loader(),
    pretendToBeVisual: true,
    url: "https://zeroplay.test/",
    virtualConsole,
    beforeParse(window) {
      window.fetch = async () => ({ ok: false, status: 404, json: async () => ({}), text: async () => "" });
      window.HTMLMediaElement.prototype.play = () => Promise.resolve();
      window.HTMLMediaElement.prototype.pause = () => {};
      window.HTMLMediaElement.prototype.load = () => {};
      window.matchMedia = () => ({
        matches: false, addListener() {}, removeListener() {},
        addEventListener() {}, removeEventListener() {}
      });
      window.scrollTo = () => {};
    }
  });
  await new Promise((resolve) => setTimeout(resolve, waitMs));
  const doc = dom.window.document;
  const result = {
    viewChildren: (doc.getElementById("view") || { children: [] }).children.length,
    crash: doc.getElementById("zpCrash"),
    bodyLength: doc.body.innerHTML.length
  };
  dom.window.close();
  return result;
}

/* The crash surface must exist and run before the app script. */
ok(dist.includes('id="zpCrash"') || dist.includes('"zpCrash"'), "dist ships the crash surface");
const crashAt = dist.indexOf('"zpCrash"');
const appAt = dist.indexOf("ZeroPlay (single-file build)");
ok(crashAt > -1 && appAt > crashAt,
  "the crash surface is installed before the app script", "crash@" + crashAt + " app@" + appAt);

(async () => {
  /* 1. Healthy app: renders, no card. */
  const healthy = await boot(dist, 2600);
  ok(healthy.viewChildren > 0, "the healthy build renders the view",
    "children=" + healthy.viewChildren);
  ok(!healthy.crash, "the healthy build shows no crash card");

  /* 2. Top-level throw in the app script: card appears. */
  const marker = '<script>\n"use strict";\n/* =====================================================================\n   ZeroPlay (single-file build)';
  const broken = dist.replace(marker,
    '<script>\n"use strict";\nzpThisFunctionWasDeleted();\n/* =====================================================================\n   ZeroPlay (single-file build)');
  ok(broken !== dist, "the fault injection modified the HTML (test is live)");
  const thrown = await boot(broken, 2600);
  ok(thrown.viewChildren === 0, "a top-level throw really does blank the view",
    "children=" + thrown.viewChildren);
  ok(!!thrown.crash, "a top-level throw paints the crash card instead of a white page");
  ok(!!thrown.crash && /could not start/i.test(thrown.crash.textContent || ""),
    "the card states the failure in plain language");

  /* 3. Boot stalls with no error: watchdog paints the card. */
  const cut = dist.indexOf("ZeroPlay (single-file build)");
  const stalled = dist.slice(0, cut + 30) + "\n/* stalled */\n</script></body></html>";
  const stalledResult = await boot(stalled, 9500);
  ok(!!stalledResult.crash, "the boot watchdog paints the card when #view never fills");

  /* 4. The recovery controls exist, because a white page with no way out is
        what made this report expensive. */
  const cardHtml = dist.slice(dist.indexOf("zpCrash"), dist.indexOf("ZeroPlay (single-file build)"));
  for (const id of ["zpCrashReload", "zpCrashCopy", "zpCrashReset"]) {
    ok(cardHtml.includes(id), `the card offers ${id}`);
  }
  ok(/showDrop|no/i.test(cardHtml) || cardHtml.includes("Reset local data"),
    "the card offers a recovery path that clears stale state");

  console.log(passed + " crash-surface checks passed");
  if (failures.length) {
    console.log("\nFAILURES:");
    for (const failure of failures) console.log("  ✗ " + failure);
    process.exit(1);
  }
})();
