#!/usr/bin/env node
// Fail-closed dependency vulnerability gate for the JavaScript toolchain.
//
// Why this exists instead of `npm audit --audit-level=high`:
//
//   `npm audit` exits non-zero for *any* high advisory anywhere in the tree, including
//   advisories that have no published fix and that are unreachable in this repository.
//   A gate that can never pass gets bypassed (`|| true`) or ignored, and a gate that
//   tolerates everything is theatre. This script keeps the strict default and makes the
//   only exemptions explicit, narrow, reviewable and self-expiring:
//
//     * high/critical advisories fail unless they appear in ACCEPTED with a written reason;
//     * an ACCEPTED entry that is *not* observed fails, so the list cannot go stale;
//     * every accepted package is verified absent from the production dependency tree
//       (`npm ls <pkg> --omit=dev`), so a build-time exemption can never cover shipped code;
//     * a failed audit run (offline, registry error, malformed output) is a failure, not a
//       pass — the gate never reports success because it could not check anything.
//
// Usage:
//   node tools/security/npm_audit_gate.mjs [--audit-json <file>] [--print-accepted]
//
// `--audit-json` exists so the gate's own behaviour can be tested with fixture reports,
// and `--print-accepted` lets those tests build fixtures from the real allowlist.

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import process from "node:process";

// Severities that block the gate when they are not explicitly accepted below.
const BLOCKING_SEVERITIES = new Set(["high", "critical"]);

// Advisories this repository deliberately tolerates. Each entry needs a reason that
// explains why it cannot be fixed *and* why it cannot be reached. Keep this list empty
// unless both are true; it is checked for staleness on every run.
const ACCEPTED = [
  {
    id: "GHSA-vfj7-8cjw-p6xm",
    package: "braces",
    reason:
      "No patched release exists (braces 3.0.3 is the newest published version and the " +
      "advisory lists no first-patched version). The only path to it is " +
      "knip -> fast-glob -> micromatch -> braces, a devDependency used for dead-code " +
      "detection; no shipped artefact and no user input reaches it. The production " +
      "dependency tree is empty and verified on every run.",
  },
];

function fail(lines) {
  for (const line of lines) process.stderr.write(`[VIOLATION] ${line}\n`);
  process.stderr.write("[FAIL] dependency vulnerability gate: blocking advisories present\n");
  process.exit(1);
}

function runNpmAudit() {
  try {
    return execFileSync("npm", ["audit", "--json"], {
      encoding: "utf8",
      maxBuffer: 64 * 1024 * 1024,
    });
  } catch (error) {
    // `npm audit` exits 1 when it finds vulnerabilities, so stdout is still the report.
    if (typeof error.stdout === "string" && error.stdout.trim() !== "") return error.stdout;
    return "";
  }
}

// Extract advisory objects from a report. Packages that only inherit an advisory from a
// dependency appear in `via` as a bare string; those are not advisories themselves.
function advisoriesFrom(report) {
  const advisories = new Map();
  for (const [name, entry] of Object.entries(report.vulnerabilities ?? {})) {
    for (const via of entry.via ?? []) {
      if (typeof via !== "object" || via === null) continue;
      const id =
        String(via.url ?? "")
          .split("/")
          .pop() ?? "";
      if (id === "") continue;
      if (!advisories.has(id)) {
        advisories.set(id, {
          id,
          package: name,
          severity: String(via.severity ?? entry.severity ?? "unknown"),
          title: String(via.title ?? ""),
        });
      }
    }
  }
  return advisories;
}

function packageReachesProduction(name) {
  try {
    const output = execFileSync("npm", ["ls", name, "--omit=dev", "--json"], {
      encoding: "utf8",
      maxBuffer: 16 * 1024 * 1024,
    });
    return output.includes(`"${name}"`);
  } catch (error) {
    const stdout = typeof error.stdout === "string" ? error.stdout : "";
    if (stdout.trim() === "") {
      // The command failed without a report: refuse to treat that as "not in production".
      fail([`could not determine whether '${name}' is a production dependency`]);
    }
    return stdout.includes(`"${name}"`);
  }
}

function main() {
  const argv = process.argv.slice(2);
  if (argv.includes("--print-accepted")) {
    process.stdout.write(`${JSON.stringify(ACCEPTED, null, 2)}\n`);
    return;
  }

  for (const entry of ACCEPTED) {
    if (typeof entry.reason !== "string" || entry.reason.length < 80) {
      fail([`accepted advisory '${entry.id}' needs a written reason of at least 80 characters`]);
    }
  }

  const auditJsonIndex = argv.indexOf("--audit-json");
  const fixture = auditJsonIndex === -1 ? null : argv[auditJsonIndex + 1];
  if (auditJsonIndex !== -1 && (fixture === undefined || fixture.startsWith("--"))) {
    fail(["--audit-json needs a file path"]);
  }

  let report;
  try {
    report = JSON.parse(fixture === null ? runNpmAudit() : readFileSync(fixture, "utf8"));
  } catch (error) {
    fail([`the audit report could not be read: ${error.message}`]);
  }
  if (report.error) {
    fail([`npm audit could not complete: ${JSON.stringify(report.error)}`]);
  }
  if (report.vulnerabilities === undefined && report.metadata === undefined) {
    fail(["the audit report carried neither vulnerabilities nor metadata"]);
  }

  const observed = advisoriesFrom(report);
  const acceptedById = new Map(ACCEPTED.map((entry) => [entry.id, entry]));
  const violations = [];

  for (const advisory of observed.values()) {
    const accepted = acceptedById.get(advisory.id);
    if (accepted === undefined) {
      if (BLOCKING_SEVERITIES.has(advisory.severity)) {
        violations.push(
          `unaccepted ${advisory.severity} advisory ${advisory.id} on '${advisory.package}' ` +
            `(${advisory.title})`,
        );
      } else {
        process.stdout.write(
          `[NOTE] non-blocking ${advisory.severity} advisory ${advisory.id} on ` +
            `'${advisory.package}'\n`,
        );
      }
      continue;
    }
    if (BLOCKING_SEVERITIES.has(advisory.severity)) {
      process.stdout.write(
        `[NOTE] accepted ${advisory.severity} advisory ${advisory.id} on ` +
          `'${advisory.package}' (no patched release)\n`,
      );
      // A fixture report says nothing about the dependency tree, so the production check
      // only runs against real `npm ls` output.
      if (fixture === null && packageReachesProduction(accepted.package)) {
        violations.push(
          `accepted advisory ${accepted.id} reaches the production dependency tree via ` +
            `'${accepted.package}'`,
        );
      }
    }
  }

  for (const entry of ACCEPTED) {
    if (!observed.has(entry.id)) {
      violations.push(
        `accepted advisory ${entry.id} was not reported by npm audit; remove the stale entry`,
      );
    }
  }

  if (violations.length > 0) fail(violations);
  process.stdout.write(
    `[PASS] dependency vulnerability gate: ${observed.size} advisories reported, ` +
      `${ACCEPTED.length} explicitly accepted (dev-only, no fix available)\n`,
  );
}

main();
