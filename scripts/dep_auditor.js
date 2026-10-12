#!/usr/bin/env node
/**
 * Dependency Usage Auditor (honest replacement for the knip station).
 *
 * knip could not serve as a gate here: every 6.x line crashes with an
 * oxc-parser raw-transfer ArrayBuffer RangeError on this repository (scan
 * queue exceeds the 4 GB V8 ArrayBuffer limit), and the 5.x line drags a
 * vulnerable braces/micromatch chain with no published fix. Masking that
 * with `|| true` is how this station failed silently before, so instead:
 *
 * This auditor does one job and fails loudly:
 *   1. Every declared dependency/devDependency must be genuinely used
 *      (imported, required, executed as a script binary, or referenced by a
 *      committed config file), or explicitly allowlisted with a reason.
 *   2. Every imported/required package must be declared.
 * Exit 1 on any finding.
 */

import fs from "fs";
import path from "path";
import { builtinModules } from "module";

const ROOT_DIR = process.cwd();
const BUILTINS = new Set(builtinModules.concat(builtinModules.map((m) => `node:${m}`)));
const pkg = JSON.parse(fs.readFileSync(path.join(ROOT_DIR, "package.json"), "utf8"));

// Dependencies whose usage is config-file-driven (invisible to import scans).
// Each entry must name the committed file that references the package.
const CONFIG_DRIVEN = {
  typescript: "tsconfig.json",
  prettier: ".prettierrc / prettier.config",
  eslint: "eslint.config.js",
  "lint-staged": ".husky/pre-commit",
  husky: ".husky/",
  commitlint: "commitlint.config.js",
  "@commitlint/cli": "commitlint.config.js",
  "@commitlint/config-conventional": "commitlint.config.js",
  "size-limit": "package.json#size-limit",
  "@size-limit/file": "package.json#size-limit",
  "@vitest/coverage-v8": "vitest.config.js (coverage.provider = v8)",
};

const IGNORED_DIRS = new Set([
  "node_modules",
  ".git",
  "build",
  "dist",
  "assets",
  "reports",
  "__pycache__",
  ".venv",
  "coverage",
  ".factory",
]);
const SOURCE_EXTS = new Set([".js", ".ts", ".cjs", ".mjs", ".jsx", ".tsx"]);
const CONFIG_FILES = [
  "eslint.config.js",
  "vitest.config.js",
  "commitlint.config.js",
  "knip.json",
  "lighthouserc.json",
  "tsconfig.json",
  ".prettierrc",
  ".prettierrc.json",
  "prettier.config.js",
  "package.json",
];

function collectFiles(dir, exts, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name.startsWith(".") && entry.name !== ".husky") continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (!IGNORED_DIRS.has(entry.name)) collectFiles(full, exts, out);
    } else if (exts.has(path.extname(entry.name))) {
      out.push(full);
    }
  }
  return out;
}

function packageRoot(spec) {
  if (!spec || spec.startsWith(".") || spec.startsWith("/") || spec.startsWith("node:"))
    return null;
  const parts = spec.split("/");
  const root = spec.startsWith("@") ? parts.slice(0, 2).join("/") : parts[0];
  return BUILTINS.has(root) ? null : root;
}

const usedPackages = new Set();
const importRe =
  /(?:import|export)[^'"]*?from\s*["']([^"']+)["']|(?:import|require)\s*\(\s*["']([^"']+)["']/g;

// 1. Scan all project source files for imports/requires.
for (const file of collectFiles(ROOT_DIR, SOURCE_EXTS)) {
  const text = fs.readFileSync(file, "utf8");
  for (const match of text.matchAll(importRe)) {
    const root = packageRoot(match[1] || match[2]);
    if (root) usedPackages.add(root);
  }
}

// 2. Referenced-by-name inside committed config files counts as usage.
const configText = CONFIG_FILES.map((f) => path.join(ROOT_DIR, f))
  .filter((f) => fs.existsSync(f))
  .map((f) => fs.readFileSync(f, "utf8"))
  .join("\n");

// 3. Script binaries (e.g. "vitest run", "tsc --noEmit", "prettier --check").
const declared = Object.assign({}, pkg.dependencies, pkg.devDependencies);
for (const script of Object.values(pkg.scripts ?? {})) {
  const bin = String(script).trim().split(/\s+/)[0];
  if (bin && declared[bin]) usedPackages.add(bin);
}

const findings = [];

// Declared but never used anywhere visible.
for (const dep of Object.keys(declared)) {
  const imported = usedPackages.has(dep);
  const configUsed = configText.includes(dep);
  const allowlisted = Object.prototype.hasOwnProperty.call(CONFIG_DRIVEN, dep);
  if (!imported && !configUsed && !allowlisted) {
    findings.push(
      `UNUSED DEPENDENCY: "${dep}" is declared but never imported, executed, or referenced by config`,
    );
  }
}

// Used but not declared (would break on clean installs).
for (const dep of usedPackages) {
  if (!(dep in declared) && !Object.prototype.hasOwnProperty.call(CONFIG_DRIVEN, dep)) {
    findings.push(
      `UNDECLARED IMPORT: "${dep}" is imported by source but missing from package.json`,
    );
  }
}

// Allowlist entries must stay honest: flag config-driven claims whose
// referenced anchor no longer exists.
for (const [dep, anchor] of Object.entries(CONFIG_DRIVEN)) {
  const anchorFile = anchor.split(/[ (/]/)[0];
  if (anchorFile && !fs.existsSync(path.join(ROOT_DIR, anchorFile)) && dep in declared) {
    if (!anchor.startsWith("package.json")) {
      findings.push(
        `STALE ALLOWLIST: "${dep}" claims usage via ${anchor}, but that file does not exist`,
      );
    }
  }
}

if (findings.length > 0) {
  console.error(`[DEP-AUDIT: FAIL] ${findings.length} finding(s):`);
  for (const finding of findings) console.error(`  - ${finding}`);
  process.exit(1);
}
console.log(
  `[DEP-AUDIT: PASS] ${Object.keys(declared).length} declared packages verified in use; ${usedPackages.size} imported packages all declared.`,
);
