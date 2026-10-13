# Savage Tech Corner — Detailed Criticism Check

> **Temporary review artifact. Preserved from Linear Issue SAV-47 attachment.**

## Review scope

- **Repository:** `regularshowrigby8-create/SAVAGE-TECH-CORNER-`
- **Branch:** `main`
- **Commit reviewed:** `db2ace9` (`fix(ci): add python dependencies step to factory-2-debug workflow`)
- **Review date:** 2026-10-11
- **Method:** Clean checkout refresh, locked Node install, Python compilation, Python unit tests, repository gates, JavaScript quality commands, dependency audit, workflow/configuration inspection, Android/release-tool inspection, and generated-artifact side-effect inspection.

## Executive verdict

The repository is a **substantial prototype with several working Python components**, but it is **not yet a trustworthy production release pipeline**. The authoritative gate still fails from clean checkouts without global pip modules, JavaScript quality tooling has broken scripts/missing packages, CI masks failures, the native WebView host allowlist is not enforced, autonomous proposal verification is too permissive, APK signing is not reproducible, and the new automated-review workflow can report successful-looking reviewer output even when the checks fail.

## Findings Summary & Action Items

| ID      | Severity | Finding                                                                                                                                       |
| ------- | -------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| **C0**  | CRITICAL | Master quality gate fails from clean checkout without pinned pip modules (`coverage`, `androguard`).                                          |
| **C1**  | CRITICAL | CI workflows mask failures using `\|\| true`, `continue-on-error`, and fallback `npm install`.                                                |
| **H1**  | HIGH     | Native WebView navigation does not enforce documented host allowlist (`MainActivity.java`).                                                   |
| **H2**  | HIGH     | Autonomous proposal verifier (`main_agent_verifier.py`) writes code after syntax-only checks without running tests or strict boundary checks. |
| **H3**  | HIGH     | APK signing creates ephemeral keys on every build rather than stable release keystore.                                                        |
| **H4**  | HIGH     | Android VM alignment check (`android_vm_verifier.py`) silently passes misaligned entries with `pass`.                                         |
| **H5**  | HIGH     | Security agent reports 0 findings while missing native WebView allowlist policy gaps.                                                         |
| **H6**  | HIGH     | JavaScript test suite missing `jsdom` dependency for `dashboard.test.cjs`.                                                                    |
| **H7**  | HIGH     | E2E configuration broken (`playwright test` command mismatch / unlisted package).                                                             |
| **H8**  | HIGH     | TypeScript strict check TS18003: no input files matching globs.                                                                               |
| **H9**  | HIGH     | ESLint configuration misclassifies `.cjs` CommonJS files (16 errors).                                                                         |
| **H10** | HIGH     | Formatting check reports 97 unformatted tracked files.                                                                                        |
| **H11** | HIGH     | Dual-review workflow sets `continue-on-error: true` and logs success on failure.                                                              |
| **M1**  | MEDIUM   | Verification runs mutate tracked release artifacts in working tree (`git status` dirty).                                                      |
| **M2**  | MEDIUM   | Conveyor belt stages inspect configuration presence instead of executing real tools.                                                          |
| **M3**  | MEDIUM   | Duplicate factory source trees (`factory-2` vs `factories/factory-2`).                                                                        |
| **M4**  | MEDIUM   | Release artifacts committed in multiple locations.                                                                                            |
| **M5**  | MEDIUM   | Mixed prototype and production claims in documentation.                                                                                       |
| **M6**  | MEDIUM   | End-to-end tests have environment-sensitive assertions and side effects.                                                                      |
