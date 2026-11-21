/* No top-level constant may be read before its declaration runs.

   The bug this prevents: `settings.sites=(settings.sites||[]).filter(site=>!isLegacyEmbedHost(...))`
   sat ~900 lines above `const LEGACY_EMBED_HOSTS`, so the first saved site
   threw "Cannot access 'LEGACY_EMBED_HOSTS' before initialization". That is a
   temporal dead zone error — the binding exists but is uninitialised — and it
   killed the whole app script at load. The page kept its static shell and
   never painted, which read to the viewer as "the catalogue is empty".

   It survived every test for one reason: `.filter()` never invokes its
   callback on an empty array, and every test started from an empty registry.

   The runtime half of this is in tests/stored-state.test.cjs, which boots the
   built page with a populated registry. This half checks the general rule at
   the source level, where it covers every top-level name at once. */
const { appScript } = require('./app-script.cjs');

let n = 0;
const ok = (c, l) => { if (!c) { console.error('FAIL', l); process.exit(1); } n++; console.log('PASS', l); };

const lines = appScript().split('\n');

/* Blank out comments and string/template literals, tracking block comments
   across lines, so prose can never look like code. */
let inBlock = false;
const cleaned = lines.map((line) => {
  let out = '', i = 0;
  while (i < line.length) {
    if (inBlock) { const e = line.indexOf('*/', i); if (e < 0) { i = line.length; } else { inBlock = false; i = e + 2; } continue; }
    if (line.startsWith('/*', i)) { inBlock = true; i += 2; continue; }
    if (line.startsWith('//', i)) break;
    const ch = line[i];
    if (ch === '"' || ch === "'" || ch === '`') {
      const q = ch; i++;
      while (i < line.length) { if (line[i] === '\\') { i += 2; continue; } if (line[i] === q) { i++; break; } i++; }
      out += 'X'; continue;
    }
    out += ch; i++;
  }
  return out;
});

/* Brace depth at the start of each line. A use at depth > 0 is inside a
   function or block, which runs later, so it cannot be a load-order error. */
let depth = 0;
const depthAt = [];
for (let i = 0; i < cleaned.length; i++) {
  depthAt[i] = depth;
  for (const ch of cleaned[i]) { if (ch === '{') depth++; else if (ch === '}') depth = Math.max(0, depth - 1); }
}

/* Top-level declarations only: column 0, so function bodies are excluded. */
const declarations = [];
lines.forEach((line, i) => {
  const m = line.match(/^(?:const|let)\s+([A-Za-z_$][\w$]*)\s*=/);
  if (m) declarations.push({ name: m[1], line: i + 1 });
});
ok(declarations.length > 100, `read the top-level declarations (${declarations.length})`);

/* `$` is a real helper here, but "not preceded by an identifier character"
   cannot tell it apart from a regex end-anchor (`/^(1|true|yes)$/`), and one
   name is not worth a JavaScript parser. It is declared before any code that
   could run, and the runtime half of this suite would still catch a real
   regression involving it. */
const SKIP = new Set(['$']);

function usedOn(text, name) {
  const re = new RegExp('(^|[^\\w$])' + name.replace(/\$/g, '\\$') + '(?![\\w$])', 'g');
  let m;
  while ((m = re.exec(text))) {
    const before = m[1];
    const after = text.slice(m.index + m[0].length);
    if (before === '.') continue;            /* property access, not a read of the binding */
    if (/^\s*:/.test(after)) continue;       /* object literal key */
    return true;
  }
  return false;
}

const offenders = [];
for (const d of declarations) {
  if (SKIP.has(d.name)) continue;
  for (let i = 0; i < d.line - 1; i++) {
    if (depthAt[i] !== 0) continue;
    if (!cleaned[i].trim()) continue;
    if (usedOn(cleaned[i], d.name)) { offenders.push(`${d.name} read at line ${i + 1}, declared at ${d.line}`); break; }
  }
}
offenders.forEach(o => console.error('  ' + o));
ok(offenders.length === 0, 'no top-level const/let is read before its declaration');

/* Name the pair that caused the outage, so a refactor cannot quietly undo it. */
const legacyDecl = lines.findIndex(l => /^const LEGACY_EMBED_HOSTS\s*=/.test(l)) + 1;
const legacyUse = lines.findIndex(l => /isLegacyEmbedHost\s*\(/.test(l)) + 1;
ok(legacyDecl > 0, 'LEGACY_EMBED_HOSTS is still declared');
ok(legacyDecl < legacyUse, `LEGACY_EMBED_HOSTS (line ${legacyDecl}) is declared before its first use (line ${legacyUse})`);

console.log(`\n${n} load-order checks passed`);
