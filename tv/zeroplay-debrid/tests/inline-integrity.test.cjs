"use strict";
/**
 * Integrity checks for the one inline <script> in index.html.
 *
 * Written after a deleted function shipped: `playerSheetVisible()` was removed
 * while call sites survived, and a bare reference to it threw ReferenceError at
 * the top level of the inline script — killing the module and leaving a blank
 * page. Every existing suite passed, because they are static greps or jsdom runs
 * that stub `fetch` and never mount the player.
 *
 * This suite cannot prove the app works. It proves the script is not
 * structurally broken, which is the class of failure that produces a white page.
 */

const fs = require("fs");
const path = require("path");
const vm = require("vm");

const ROOT = path.join(__dirname, "..");
const html = fs.readFileSync(path.join(ROOT, "index.html"), "utf8");

let passed = 0;
const failures = [];
function ok(condition, label, detail) {
  if (condition) passed += 1;
  else failures.push(label + (detail ? " — " + detail : ""));
}

const scriptMatch = /<script>\n([\s\S]*?)\n<\/script>/.exec(html);
ok(!!scriptMatch, "index.html contains one inline script block");
const body = scriptMatch ? scriptMatch[1] : "";

/* 1. parses */
let parseError = "";
try { new vm.Script(body, { filename: "inline.js" }); } catch (e) { parseError = e.message; }
ok(!parseError, "the inline script parses", parseError);

/* 2. declarations */
function declarationsOf(source) {
  const names = new Map();
  const patterns = [
    /^(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(/gm,
    /^(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=/gm,
    /^(?:const|let|var)\s+\{([^}]+)\}\s*=/gm
  ];
  for (const pattern of patterns) {
    let m;
    while ((m = pattern.exec(source))) {
      for (const entry of m[1].split(",")) {
        const name = entry.split(":")[0].split("=")[0].trim();
        if (/^[A-Za-z_$][\w$]*$/.test(name)) names.set(name, (names.get(name) || 0) + 1);
      }
    }
  }
  return names;
}
const declared = declarationsOf(body);

/*
 * The scan above only sees top-level declarations. `tick`, `fill`, `syncHash`
 * and friends are declared *inside* function bodies, which is where most of this
 * app lives, so a reference to them is perfectly valid. Collect every
 * declaration at any depth before calling anything dangling.
 */
const allNames = new Set(declared.keys());
{
  const anyDepth = [
    /(?:async\s+)?function\s*\*?\s*([A-Za-z_$][\w$]*)\s*\(/g,
    /\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=/g,
    /\bclass\s+([A-Za-z_$][\w$]*)/g,
    /\bcatch\s*\(\s*([A-Za-z_$][\w$]*)/g,
    /\bfunction\s*[A-Za-z_$]*\s*\(([^)]*)\)/g,
    /\(([^)]*)\)\s*=>/g,
    /([A-Za-z_$][\w$]*)\s*=>/g,
    /* Shorthand methods: `preventDefault(){ ... }` and `async fill(){}`. */
    /(?:^|[{,;\n])\s*(?:async\s+|static\s+|get\s+|set\s+)?\*?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{/g
  ];
  const stripped = stripLiterals(body);
  for (const re of anyDepth) {
    let m;
    while ((m = re.exec(stripped))) {
      for (const part of String(m[1]).split(",")) {
        const name = part.split(":")[0].split("=")[0].replace(/[^A-Za-z0-9_$]/g, "").trim();
        if (/^[A-Za-z_$][\w$]*$/.test(name)) allNames.add(name);
      }
    }
  }
}
const dupes = [...declared.entries()].filter(([, c]) => c > 1).map(([n]) => n);
ok(dupes.length === 0, "no top-level name is declared twice", dupes.join(", "));

/* 3. dangling references */
const AMBIENT = new Set(("window document console navigator location history localStorage " +
  "sessionStorage fetch Request Response Headers AbortController setTimeout clearTimeout " +
  "setInterval clearInterval requestAnimationFrame cancelAnimationFrame queueMicrotask " +
  "Promise Map Set WeakMap WeakSet Array Object String Number Boolean JSON Math Date RegExp " +
  "Error TypeError RangeError Symbol Proxy Reflect BigInt URL URLSearchParams Blob File " +
  "FileReader FormData Image Audio Event CustomEvent MouseEvent KeyboardEvent FocusEvent " +
  "PointerEvent HTMLElement Element Node MutationObserver IntersectionObserver " +
  "ResizeObserver performance crypto TextEncoder TextDecoder Intl parseInt parseFloat " +
  "isNaN isFinite encodeURIComponent decodeURIComponent encodeURI decodeURI " +
  "structuredClone atob btoa alert confirm prompt getComputedStyle matchMedia screen " +
  "devicePixelRatio MediaSource SourceBuffer WebSocket Worker Notification indexedDB " +
  "BroadcastChannel MessageChannel postMessage ZeroPlayLib Hls dashjs chrome " +
  "function if for while switch catch return typeof new await async of in do else try " +
  "throw delete void yield class extends super this arguments NaN Infinity undefined null " +
  "global this").split(/\s+/));

/*
 * Only look at *statement-position* bare calls: a name at the start of a line
 * (optionally after `return`, `await`, `typeof`, `else`, `=`), not a property
 * access, and with strings and comments stripped first. The loose version of
 * this scan flagged string contents and object methods, which is noise.
 */
function stripLiterals(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/(^|[^:])\/\/[^\n]*/g, "$1 ")
    /* Template literals first, including ${...} interpolation, since selectors
       are built inside them. */
    .replace(/`(?:\\.|\$\{[^}]*\}|[^`\\])*`/g, "``")
    .replace(/'(?:\\.|[^'\\])*'/g, "''")
    .replace(/"(?:\\.|[^"\\])*"/g, '""')
    /* Regex literals: a slash that starts an expression, not a division. */
    .replace(/([=(,:!&|?{};]\s*)\/(?:\\.|\[(?:\\.|[^\]\\])*\]|[^\/\\\n])+\/[gimsuy]*/g, "$1/RE/");
}

const code = stripLiterals(body);
const called = new Set();
const callPattern = /(?:^|[;{}(=,|&?:]|\breturn\b|\bawait\b)\s*([A-Za-z_$][\w$]*)\s*\(/gm;
let cm;
while ((cm = callPattern.exec(code))) called.add(cm[1]);

/*
 * Restrict to two precise classes so the scan cannot produce noise:
 *   a) names pinned in KNOWN_GONE, which must exist if anything calls them, and
 *   b) camelCase identifiers, which is how every function in this app is named.
 * `not`, `var` and other fragments of CSS/text are thereby excluded, and `fill`
 * is matched by the any-depth declaration scan above.
 */
const KNOWN_GONE = ["playerSheetVisible", "playerIdleStart", "playerIdleAccumulate",
  "playerChromeState", "playerChromeLocks", "playerChromeController", "applyPlayerChrome",
  "drawPlayer", "startOwnEngine"];

const missing = [...called].filter(n => {
  if (allNames.has(n) || AMBIENT.has(n)) return false;
  if (KNOWN_GONE.includes(n)) return true;
  return /^[a-z][a-z0-9]*[A-Z]/.test(n);
});
ok(missing.length === 0, "every called identifier is declared or a known global",
  missing.slice(0, 15).join(", "));

/* 4. the helpers that have to exist for the player to mount at all */
const mustExist = ["playerShell", "bindPlayerChrome", "playerCommand", "togglePlayerMore",
  "togglePlayerFullscreen", "playerChromeController", "playerChromeLocks", "playerChromeState",
  "applyPlayerChrome", "playerSheetVisible", "playerSeek", "startPlaybackWithRecovery",
  "fallbackToPlayButton", "hidePlayerChrome", "showPlayerChrome", "drawPlayer",
  "startOwnEngine", "playerIdleStart", "playerIdleAccumulate"];
for (const name of mustExist) {
  ok(declared.has(name), `${name} is defined`);
}

/* 5. bundle usage is guarded, so a failed bundle cannot blank the page */
const uses = [...new Set((body.match(/zpCore\.[A-Za-z_$][\w$]*/g) || []))];
ok(uses.length > 0, "the player core is actually consumed");
for (const use of uses) {
  const idx = body.indexOf(use);
  const window = body.slice(Math.max(0, idx - 120), idx + 40);
  const guarded = window.includes("zpCore&&") || window.includes("zpCore &&") ||
    window.includes("zpCore?") || window.includes("!zpCore") || window.includes("zpCore.createBufferingTracker?");
  ok(guarded, `${use} is reached only behind a guard`);
}

/* 6. no inline script error can leave the body empty silently */
ok(/id="view"/.test(html), "the view container is static markup, not script-generated");

console.log(passed + " inline-integrity checks passed");
if (failures.length) {
  console.log("\nFAILURES:");
  for (const f of failures) console.log("  ✗ " + f);
  process.exit(1);
}
