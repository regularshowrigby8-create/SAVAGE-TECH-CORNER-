/* TV navigation contract (Phase 1 of the home/navigation rebuild).
 *
 * The real `moveTvFocus` / scope / reveal functions are lifted out of
 * index.html and executed against a geometry-faithful fake DOM, so the D-pad
 * rules are verified by behaviour instead of by grepping for source strings.
 *
 * Layout under test is a 1920x1080 Android TV page:
 *   header:  search input, Search button, settings button
 *   row 1:   6 poster cards   (x = 60, 194, 328, 462, 596, 730 ; y 260-458)
 *   row 2:   6 poster cards   (same x, y 500-698)
 *   overlay: a Details modal and a drawer that must own focus when open
 */
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const assert = require("node:assert/strict");
const html = fs.readFileSync(path.join(__dirname, "..", "index.html"), "utf8");
const script = html.slice(html.indexOf("<script>") + 8, html.lastIndexOf("</script>"));

let passed = 0;
const ok = (condition, label) => {
  assert(condition, label);
  passed++;
  console.log("PASS " + label);
};

/* ---------------------------------------------------------------- DOM ---- */
class FakeClassList {
  constructor(node) {
    this.node = node;
    this.set = new Set();
  }
  add(...names) {
    names.forEach((n) => this.set.add(n));
  }
  remove(...names) {
    names.forEach((n) => this.set.delete(n));
  }
  contains(name) {
    return this.set.has(name);
  }
  toggle(name, force) {
    const on = force === undefined ? !this.set.has(name) : !!force;
    if (on) this.set.add(name);
    else this.set.delete(name);
    return on;
  }
  get value() {
    return [...this.set].join(" ");
  }
}

class FakeNode {
  constructor(name, options = {}) {
    this.tagName = (options.tag || "DIV").toUpperCase();
    this.id = options.id || "";
    this.className = options.className || "";
    this.dataset = options.dataset || {};
    this.children = [];
    this.parentElement = null;
    this.rect = options.rect || { left: 0, top: 0, width: 0, height: 0 };
    this.disabled = !!options.disabled;
    this.hidden = false;
    this.attrs = Object.assign({}, options.attrs);
    this.classList = new FakeClassList(this);
    (options.className || "").split(/\s+/).filter(Boolean).forEach((c) => this.classList.add(c));
    this.isConnected = true;
    this.scrollTop = 0;
    this.scrollLeft = 0;
    this.style = options.style || {};
    this.selectorIndex = null; // filled in by the document
  }
  getBoundingClientRect() {
    const r = this.rect;
    return {
      left: r.left,
      top: r.top,
      width: r.width,
      height: r.height,
      right: r.left + r.width,
      bottom: r.top + r.height,
      x: r.left,
      y: r.top,
    };
  }
  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attrs, name) ? this.attrs[name] : null;
  }
  setAttribute(name, value) {
    this.attrs[name] = String(value);
  }
  append(...nodes) {
    nodes.forEach((n) => {
      n.parentElement = this;
      this.children.push(n);
    });
    return this;
  }
  contains(node) {
    let cur = node;
    while (cur) {
      if (cur === this) return true;
      cur = cur.parentElement;
    }
    return false;
  }
  closest(selector) {
    return this.ownerDocument ? this.ownerDocument.closestFor(this, selector) : null;
  }
  matches(selector) {
    return this.ownerDocument ? this.ownerDocument.matches(this, selector) : false;
  }
  querySelectorAll(selector) {
    return this.ownerDocument ? this.ownerDocument.querySelectorAllIn(this, selector) : [];
  }
  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }
  focus() {
    this.ownerDocument.activeElement = this;
  }
  scrollIntoView() {}
  addEventListener() {}
}

class FakeDocument {
  constructor() {
    this.body = new FakeNode("body", { className: "android-tv" });
    this.body.ownerDocument = this;
    this.activeElement = this.body;
    this.byId = new Map();
    this.all = [this.body];
    this.documentElement = {
      scrollWidth: 1920,
      getBoundingClientRect: () => ({ left: 0, top: 0, right: 1920, bottom: 1080, width: 1920, height: 1080 }),
    };
    this.fullscreenElement = null;
  }
  create(options) {
    const node = new FakeNode(options.name || "node", options);
    node.ownerDocument = this;
    this.all.push(node);
    if (options.parent) options.parent.append(node);
    if (node.id) this.byId.set(node.id, node);
    return node;
  }
  getElementById(id) {
    return this.byId.get(id) || null;
  }
  /* tiny selector engine: only the selectors the contract actually uses */
  matches(node, selector) {
    const parts = selector.split(",").map((s) => s.trim()).filter(Boolean);
    return parts.some((part) => matchOne(node, part));
  }
  closestFor(node, selector) {
    let cur = node;
    while (cur) {
      if (this.matches(cur, selector)) return cur;
      cur = cur.parentElement;
    }
    return null;
  }
  querySelectorAllIn(root, selector) {
    const out = [];
    const walk = (node) => {
      node.children.forEach((child) => {
        if (selector && this.matches(child, selector)) out.push(child);
        walk(child);
      });
    };
    if (selector) walk(root);
    else walk(root);
    return out;
  }
  querySelectorAll(selector) {
    return this.querySelectorAllIn(this.body, selector);
  }
  querySelectorAllInScope(root, selector) {
    return this.querySelectorAllIn(root, selector);
  }
  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }
  addEventListener() {}
  dispatchEvent() {}
}

function matchAttr(node, selector) {
  const attr = selector.match(/^\[([^=\]]+)='([^']*)'\]$/);
  if (attr) return node.getAttribute(attr[1]) === attr[2];
  const named = selector.match(/^\[([^=\]]+)\]$/);
  if (named) return node.getAttribute(named[1]) !== null;
  return false;
}
/* Supports exactly the selector shapes in TV_FOCUSABLE_SELECTOR:
   tag, .class, [attr], [attr='v'] and a single trailing :not([...]). */
function matchOne(node, selector) {
  let sel = selector.trim();
  const negations = [];
  let not = sel.match(/:not\((\[[^\]]*\])\)$/);
  while (not) {
    negations.push(not[1]);
    sel = sel.slice(0, not.index).trim();
    not = sel.match(/:not\((\[[^\]]*\])\)$/);
  }
  if (negations.some((n) => matchAttr(node, n))) return false;
  if (!sel) return false;
  if (sel.startsWith("[")) return matchAttr(node, sel);
  const cls = sel.match(/^\.([A-Za-z0-9_-]+)$/);
  if (cls) return node.classList.contains(cls[1]);
  const tag = sel.match(/^([A-Za-z]+)$/);
  if (tag) return node.tagName === tag[1].toUpperCase();
  return false;
}

/* ------------------------------------------------------------- harness --- */
function loadContract(doc) {
  const start = script.indexOf("const TV_FOCUS_SCOPES=[");
  const endSrc = script.indexOf("function handlePlayerKeyboard(e)");
  const block = script.slice(start, endSrc);
  const sandbox = {
    document: doc,
    window: { innerHeight: 1080, innerWidth: 1920, getComputedStyle: (node) => styleOf(node) },
    getComputedStyle: (node) => styleOf(node),
    ANDROID_TV_APP: true,
    settings: { ui: {} },
    playerState: null,
    detailState: null,
    streamsCtx: null,
    downloadModalState: null,
    tvPerformanceEnabled: () => true,
    Math,
    Number,
    console,
    setTimeout,
    clearTimeout,
  };
  sandbox.window.scrollX = 0;
  sandbox.window.scrollY = 0;
  vm.createContext(sandbox);
  vm.runInContext(block, sandbox);
  return sandbox;
}
function styleOf(node) {
  const overflow = node.classList.contains("rail-track") || node.classList.contains("scroll") ? "auto" : "visible";
  return {
    display: node.hidden || node.style.display === "none" ? "none" : "block",
    visibility: "visible",
    opacity: String(node.style.opacity === undefined ? 1 : node.style.opacity),
    overflowX: overflow,
    overflowY: overflow,
  };
}

/* --------------------------------------------------------------- page ---- */
function buildPage() {
  const doc = new FakeDocument();
  const header = doc.create({ id: "topbar", className: "topbar", parent: doc.body, rect: { left: 288, top: 0, width: 1632, height: 96 } });
  const inner = doc.create({ className: "topbar-inner", parent: header, rect: { left: 288, top: 0, width: 1632, height: 96 } });
  const search = doc.create({
    tag: "input",
    id: "topQuery",
    parent: inner,
    rect: { left: 900, top: 24, width: 420, height: 48 },
    attrs: { tabindex: "0" },
  });
  const searchBtn = doc.create({ tag: "button", id: "searchGo", parent: inner, rect: { left: 1330, top: 24, width: 90, height: 48 } });
  const settingsBtn = doc.create({ tag: "button", id: "openSettings", parent: inner, rect: { left: 1440, top: 24, width: 90, height: 48 } });

  const view = doc.create({ id: "view", parent: doc.body, rect: { left: 288, top: 96, width: 1632, height: 2000 } });
  const railOne = doc.create({ className: "rail-track", parent: view, rect: { left: 288, top: 260, width: 1632, height: 198 } });
  const railTwo = doc.create({ className: "rail-track", parent: view, rect: { left: 288, top: 500, width: 1632, height: 198 } });
  const rowOne = [];
  const rowTwo = [];
  for (let i = 0; i < 6; i += 1) {
    rowOne.push(
      doc.create({
        tag: "article",
        className: "poster-card",
        parent: railOne,
        rect: { left: 300 + i * 134, top: 260, width: 132, height: 198 },
        attrs: { tabindex: "0" },
        dataset: { type: "movie", id: String(100 + i) },
      }),
    );
    rowTwo.push(
      doc.create({
        tag: "article",
        className: "poster-card",
        parent: railTwo,
        rect: { left: 300 + i * 134, top: 500, width: 132, height: 198 },
        attrs: { tabindex: "0" },
        dataset: { type: "movie", id: String(200 + i) },
      }),
    );
  }
  /* a poster in row one with an unreachable (opacity 0) hover control:
     the old code let D-pad press into it and appear to do nothing. */
  const deadZone = doc.create({
    tag: "button",
    className: "hover-action",
    parent: rowOne[2],
    rect: { left: 570, top: 400, width: 90, height: 40 },
    style: { opacity: 0 },
  });
  return { doc, search, searchBtn, settingsBtn, view, rowOne, rowTwo, deadZone };
}

const page = buildPage();
const ctx = loadContract(page.doc);
ctx.document.body.ownerDocument.activeElement = page.rowOne[1];

/* --------------------------------------------------------------- rules --- */
const centre = (node) => {
  const r = node.getBoundingClientRect();
  return [r.left + r.width / 2, r.top + r.height / 2];
};
const focusIndex = (row) => row.indexOf(page.doc.activeElement);

/* 1. Left / Right stays inside the row it started in. */
page.doc.activeElement = page.rowOne[1];
ok(ctx.moveTvFocus("right") === true, "arrow right is handled");
ok(page.doc.activeElement === page.rowOne[2], "arrow right moves one card along the shelf");
ok(ctx.moveTvFocus("left") === true && page.doc.activeElement === page.rowOne[1], "arrow left moves back one card");
page.doc.activeElement = page.rowOne[0];
ctx.moveTvFocus("left");
ok(page.doc.activeElement === page.rowOne[0], "arrow left at the first card does not leave the row");
page.doc.activeElement = page.rowOne[5];
ctx.moveTvFocus("right");
ok(page.doc.activeElement === page.rowOne[5], "arrow right at the last card does not leave the row");
ok(focusIndex(page.rowTwo) === -1, "a horizontal edge never teleports into another row");

/* 2. Down from a rail edge lands on the nearest item of the next row. */
page.doc.activeElement = page.rowOne[5];
ctx.moveTvFocus("down");
ok(page.doc.activeElement === page.rowTwo[5], "arrow down at a shelf edge lands on the closest card below, not the row start");

/* 3. Up returns to the header and Down comes back. */
page.doc.activeElement = page.rowOne[0];
ctx.moveTvFocus("up");
ok(page.doc.activeElement === page.search || page.doc.activeElement === page.settingsBtn || page.doc.activeElement === page.searchBtn, "arrow up from the first shelf reaches the header");
const headerTarget = page.doc.activeElement;
ctx.moveTvFocus("down");
ok(page.rowOne.includes(page.doc.activeElement) || page.rowTwo.includes(page.doc.activeElement), "arrow down from the header returns to the shelves");
ok(headerTarget !== null, "header focus target is a real element");

/* 4. Invisible controls are not focus stops (no dead D-pad presses). */
ok(ctx.tvIsFocusable(page.deadZone) === false, "a control with opacity 0 is not a focus stop");
const reachable = ctx.tvFocusables(page.doc);
ok(reachable.includes(page.rowOne[2]) === true, "the card that owns the hidden overlay stays reachable");
ok(reachable.includes(page.deadZone) === false, "hidden card overlay buttons are excluded from the D-pad order");

/* 5. One scope owns the D-pad, and Back unwinds it. */
const drawer = page.doc.create({
  id: "menuDrawer",
  className: "menu-drawer open",
  parent: page.doc.body,
  rect: { left: 0, top: 0, width: 420, height: 1080 },
});
const drawerItem = page.doc.create({ tag: "button", className: "menu-link", parent: drawer, rect: { left: 20, top: 120, width: 320, height: 52 } });
ok(ctx.tvScopeRoot() === drawer, "an open drawer takes the D-pad away from the page");
const drawerFocusables = ctx.tvFocusables(null);
ok(drawerFocusables.length === 1 && drawerFocusables[0] === drawerItem, "the drawer is the only focus scope while it is open");
drawer.classList.remove("open");
ok(ctx.tvScopeRoot() !== drawer, "closing the drawer returns the D-pad to the page");

const modal = page.doc.create({ id: "modalRoot", parent: page.doc.body, rect: { left: 0, top: 0, width: 1920, height: 1080 } });
const modalBtn = page.doc.create({ tag: "button", id: "detailPlay", parent: modal, rect: { left: 700, top: 500, width: 200, height: 56 } });
ctx.detailState = { type: "movie", id: 101 };
ok(ctx.tvScopeRoot() === modal, "a Details modal takes the D-pad");
ok(ctx.tvFocusables(null).every((n) => n === modalBtn), "only the modal's controls are reachable while it is open");
ctx.detailState = null;

const player = page.doc.create({ id: "playerRoot", parent: page.doc.body, rect: { left: 0, top: 0, width: 1920, height: 1080 } });
const playBtn = page.doc.create({ tag: "button", id: "btnPlayPause", parent: player, rect: { left: 900, top: 900, width: 80, height: 48 } });
ctx.playerState = { engine: "native", playback: { paused: false } };
ok(ctx.tvScopeRoot() === player, "the player outranks page content and modals in the scope order");
ok(ctx.tvFocusables(null).every((n) => n === playBtn), "player chrome is the only focus scope during playback");
ctx.playerState = null;

/* 6. Focus recovery keeps the viewer where they were. */
page.doc.activeElement = page.rowTwo[3];
ctx.rememberTvFocus(page.rowTwo[3]);
page.doc.activeElement = page.doc.body;
ctx.moveTvFocus("down");
ok(page.doc.activeElement === page.rowTwo[3], "a lost focus recovers the same card instead of jumping to the top");

/* 7. Static contract checks: the wiring is reachable, and nothing is orphaned. */
const staticChecks = [
  [typeof ctx.moveTvFocus === "function" && typeof ctx.tvFocusables === "function", "navigation contract is callable from the app"],
  [/function handleZeroPlayTvBack\(\)\{\s*\/\* 3\. Back is owned[\s\S]*?closeMenuDrawer\(\); return true;/.test(script), "Back unwinds the drawer before the page"],
  [script.indexOf('if(/^Arrow/.test(e.key)){') > 0 && script.includes("const inPage=!active||active===document.body||tvIsFocusable(active)"), "every D-pad press is routed through the navigation contract"],
  [!html.includes("currentRail&&!currentRail.contains(el)"), "the superseded row-lock implementation is gone, not duplicated"],
  [/let lastTvFocusSelector="";\s*let lastTvFocusPoint=null;\s*let lastTvFocusElement=null;\s*let tvInitialFocusTimer=null;\s*\/\* 5\. Return-home stack/.test(script), "focus state and the return-home stack are declared once"],
];
for (const [condition, label] of staticChecks) ok(condition, label);

/* 8. TV scale budget: artwork shrinks to reference sizes, icons stay legible. */
const css = html.slice(html.indexOf("<style>") + 7, html.indexOf("</style>"));
const posterBudget = css.match(/body\.android-tv,\s*body\.tv-mode\s*\{[^}]*--zp-poster-w:\s*(\d+)px/);
ok(!!posterBudget, "TV poster width is a token, not ad-hoc CSS");
ok(Number(posterBudget[1]) >= 120 && Number(posterBudget[1]) <= 150, "TV poster width sits in the 120-150px reference band (Nuvio 126dp, Stremio 8rem)");
ok(!/body\.android-tv \.rail-track \.poster-card\{flex-basis:clamp\(210px/.test(css), "the oversized 210-264px TV poster rule is gone");
ok(!/body\.android-tv \.rail-head h2\{font-size:1\.72rem/.test(css), "the oversized 1.72rem TV rail heading is gone");
ok(/--zp-icon-lg:\s*24px/.test(css) && /--zp-icon-md:\s*20px/.test(css), "TV icon scale is defined in the 20-24px band");
ok(/body\.android-tv \.zp-rail-icon,[\s\S]{0,120}?font-size:\s*var\(--zp-icon-md\)/.test(css), "sidebar icons use the icon token instead of inheriting text size");
ok(/--zp-focus-ring:\s*2px/.test(css) && /outline:\s*var\(--zp-focus-ring\) solid var\(--zp-focus\)/.test(css), "one shared focus ring is used across cards, buttons and inputs");

console.log(`\n${passed} TV navigation contract checks passed`);
