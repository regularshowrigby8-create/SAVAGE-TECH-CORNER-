# tools/CHANGES.md — Changelog

## [Enforcement v4: Fail-Closed Anti-Bypass & 5x Exhaustive Verification Engine] - 2026-10-11
- Created `tools/factory/enforce_rules.py` fail-closed verification engine.
- Mandated that no rules can be bypassed or ignored without an extremely good, comprehensive architectural justification (min 100 characters).
- Enforced that any bypass request must be accompanied by an authentic functional solution file.
- Built automated 5x exhaustive testing engine: runs the entire test suite and deterministic gates across 5 consecutive isolated iterations before issuing a cryptographic bypass certificate.
- Created unit tests in `tools/factory/tests/test_enforce_rules.py`.
- Configured git pre-commit hook `.githooks/pre-commit` to prevent committing code that fails quality gates or attempts unverified bypasses.
- All tests passing 100%.

## [Android deliverable: honest verification tooling] - 2026-10-13
- **Reason for the changes below:** the tools previously reported results they could not produce
  (a "simulated" install) or verified a stale pre-built APK by default. Acceptance criteria AC-021,
  AC-044, AC-045, AC-046 and AC-047 require evidence that comes from real artifacts, so the tools
  were aligned with what they can actually prove. No capability was removed without replacement.
- `tools/vm_crash_and_install_debugger.py` — relabelled from a "VM install & crash diagnostics"
  emulator to what it really is: a **static APK structure analyser**. Result fields were renamed
  (`installer_status` → `static_analysis_status`, `runtime_status` → `dex_linkage_status`,
  `overall_healthy` → `static_analysis_clean`), the fabricated `INSTALL_SUCCESS` value is gone, and
  the report now states explicitly that install/launch/crash evidence comes from
  `tools/android_apk_smoke.sh` and the instrumented tests. The analysis logic itself is unchanged.
- `tools/reproducible_build_verifier.py` — rewritten. It no longer defaults to
  `assets/SavageBrowser-v2.0.0.apk`; the APK must be given with `--apk` (an artefact that was not
  built by this checkout cannot verify this checkout). It now reads the expected identity
  (applicationId, versionCode/Name, min/target SDK) from `app/build.gradle.kts`, cross-checks the
  APK against the source manifest (launcher activity, permission set), verifies ZIP integrity,
  reports size/SHA-256/certificate, and fails when androguard is missing instead of reporting a
  pass without evidence. New flags: `--apk`, `--expect-signed`, `--json`.
- `tools/android_apk_smoke.sh` — **new.** Real-device smoke test on the built APK: install, launch
  the launcher activity, confirm the browser chrome from a UI hierarchy dump, kill the process and
  relaunch twice (restart persistence), and uninstall/reinstall. Every step asserts what it observed.
