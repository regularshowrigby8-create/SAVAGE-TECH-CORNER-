# Nova strict verification

This directory contains the fail-closed verification gate and the four-cell APK
checker for the pipeline-v3 worktree.

## Current local evidence

`evidence/pipeline-v3.strict.json` is the current explicit **PASS** record for
the `local` profile from base `bdf87d1d4c307e791135596883b8f2d493f29b94` to
`WORKTREE`. The full command output is in `evidence/pipeline-v3.strict.log`.

The contract is `contracts/pipeline-v3.json`. It pins every changed binary by
SHA-256, requires the shared pipeline files and the Kotlin pipeline unit test,
checks 14 stages / 65 engine configurations / the 10,000-site target, forbids
false-green wrappers, warn-only artifact uploads, skipped Python tests, and
stub markers in the pipeline. The evidence includes a SHA-256 fingerprint of
every changed source file; the gate's own evidence and log outputs are excluded
from that fingerprint so it does not reference itself.

Reproduce the local result with:

```bash
python3 tools/nova/strict_verify.py \
  --repo . \
  --contract tools/nova/contracts/pipeline-v3.json \
  --base bdf87d1d4c307e791135596883b8f2d493f29b94 \
  --head WORKTREE \
  --profile local \
  --evidence tools/nova/evidence/pipeline-v3.strict.json \
  --log tools/nova/evidence/pipeline-v3.strict.log
```

A `PASS` means exactly the checks selected by that contract and profile passed.
It does **not** mean the Android code was compiled in this sandbox.

## What the local profile cannot prove

The sandbox has no JVM, Gradle wrapper cache, Kotlin compiler, Android SDK,
`aapt2`, `zipalign`, `apksigner`, APK binaries, emulator or device. Four CI-only
commands are explicitly excluded from the local profile and listed in the
evidence under `excluded_commands`:

- `android-flavour-unit-tests`
- `android-flavour-lint`
- `android-debug-and-release-assembly`
- `artifact-four-cell-verification`

The contract's `profile_limitations` field and the evidence repeat this scope.
The deterministic `PipelineRunnerTest.kt` proves the 10,000-site capacity path
with fixture responses in source/CI; it is not a claim about live internet
reachability or an on-device run.

## APK verification

`check_artifacts.py` requires exactly one APK in each of four cells (TV debug,
TV release, mobile debug, mobile release). For each it checks archive members,
`aapt2` package id, `apksigner` signature, `zipalign` alignment, dex entries and
the source markers `documentElement.outerHTML` and
`nova-finder-pipeline-v3`. It refuses to infer an SDK path: CI passes an
explicit `NOVA_BUILD_TOOLS` directory through `run_ci_artifact_check.sh`.

The earlier successful run `45398507630` supplied artifact metadata for the
`a1c5e3d` source. Those binaries were not downloaded into this sandbox, and
their metadata is not proof that pipeline-v3 reached a dex file. CI must build
the current worktree and run the four-cell checker on those outputs.

## False-green fixes

`nova-tv/python/nova/result.py` defines explicit nested-result success;
`nova-tv/python/nova/cli.py` now returns a failing exit code when a nested
result is false. `ops/qa_offline_gates.py` runs seven offline gates and asserts
each payload, replacing a CI loop that merely grepped a top-level `"ok": true`.
The workflows now use `if-no-files-found: error`, fail Android lint on a
nonzero Gradle status, run both flavour unit-test tasks, and assert the APK
evidence JSON instead of printing a green echo.

## APK finder debug session

The APK-focused question is tracked in `nova-tv/docs/APK-FINDER-DEBUG.md`.
`crawl_probe.py` performs a bounded real crawl of the same engine registry;
`emulator_finder_smoke.sh` installs the current mobile debug APK, runs the
debug-only `FinderProbeActivity` in `pipeline` and `ui` modes, pulls app-private
JSON/trace/pool evidence, and taps the real Find sites button with UIAutomator;
`render_finder_debug_report.py` writes a committed Markdown report. The Android
workflow runs these as explicit jobs. A local static `PASS`, a fetched index URL
or a parsed candidate is never treated as on-device proof.
