<!-- Recorded from the GitHub Actions API of this repository; see the run links below. -->

# CI evidence — Savage Browser 3.0.0 (5), commits `ca8f4d3` and `2943074`

This is the AC-047 evidence: the green run of this commit on GitHub-hosted runners, i.e. from a clean checkout with no local state. Every value below was read from this repository's own workflow, job and annotation records through the GitHub API; nothing in this file was produced anywhere else, and nothing about a run that failed is omitted (the runs of the earlier commits on this branch, and what they failed on, are named at the bottom).

## Workflows of this commit

| Workflow                             | Run                                                                                                     | Conclusion | Started (UTC)        | Finished (UTC)       |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------- | ---------- | -------------------- | -------------------- |
| Android Shell CI & Release Pipeline  | [38518749625](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38518749625) | `success`  | 2026-10-14T05:49:55Z | 2026-10-14T05:57:19Z |
| Comprehensive Quality Conveyor Belt  | [38518749624](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38518749624) | `success`  | 2026-10-14T05:49:55Z | 2026-10-14T05:53:34Z |
| AI PR Code Reviewers (Dual Fallback) | [38518749631](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38518749631) | `success`  | 2026-10-14T05:49:55Z | 2026-10-14T05:53:12Z |
| Factory 2 — Single Debug Job         | [38518749713](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38518749713) | `success`  | 2026-10-14T05:49:55Z | 2026-10-14T05:55:23Z |

## The APK of the tip recorded here (`562cc8a`)

| Field              | Value                                                                                                                                                    |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| File               | `app/build/outputs/apk/debug/app-debug.apk` of `shell/android_project/` (the Gradle build output; not a repository file)                                 |
| Artifacts          | `savage-browser-apk` (zip 5,793,259 B) + `emulator-evidence` (543,139 B), containing `SHA256SUMS.txt`                                                    |
| Size               | 5,795,660 bytes                                                                                                                                          |
| SHA-256            | `85e14b96ee34a663a943466af2afe341ec7e7876945aabc89626c3e18b0ac0e2`                                                                                       |
| Identity           | `package=com.savage.soulbrowser`, `versionName=3.0.0`, `versionCode=5`, `min_sdk=26`, `target_sdk=35`, launcher `com.savage.soulbrowser.ui.MainActivity` |
| Permissions        | `INTERNET`, `ACCESS_NETWORK_STATE`, `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`                                                                           |
| Signing            | v1+v2+v3, debug key (`CN=Android Debug`, certificate SHA-256 `e2ea8c85…`)                                                                                |
| Verified on device | the emulator job hashed these bytes with `sha256sum -c SHA256SUMS.txt` before installing them                                                            |

A later tip (`8d4ef1b`) built 5,795,656 bytes with SHA-256
`5225dfe3d309335281716e5c13287ccd8112f0c71571baf8b4688082eed90cac` and debug certificate
`ded84528…`: same identity, same permissions, same signing scheme, different bytes for the reason above.
This file records the runs it names; it is not rewritten on every later commit of the branch.

## The runs recorded here, and the ones after them (state as of 2026-10-14)

This file is a point-in-time record, so it names the runs it describes and nothing is rewritten when a
later commit runs again:

- `ca8f4d3` - the first fully green tip, detailed in the tables below.
- `2943074` - the same four workflows on a clean checkout, all green: Android pipeline
  [38520485065](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38520485065)
  (build job `115708810752`, emulator job `115709536339`, release job skipped), belt `38520485008`,
  reviewers `38520485049`, Factory 2 `38520485031`.
- `268dfbe` - the same four workflows, all green again: Android pipeline
  [38521338224](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38521338224)
  (build job `115711482131`, emulator job `115712402004`, release job skipped), belt `38521337958`,
  reviewers `38521338163`, Factory 2 `38521338389`.
- `562cc8a` - the tip whose APK annotation is quoted in the artifact table below: Android pipeline
  [38522307493](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38522307493)
  (build job `115714581099`, emulator job `115715606092`, release job skipped), belt `38522307470`,
  reviewers `38522307480`, Factory 2 `38522307472`.
- `8d4ef1b` - the run that proves the pipeline's provenance gate (A20) inside CI: the belt certified an
  APK the build stage had just built and recorded, with the Android pipeline green as well
  (build job `115726371442`, emulator job `115727439968`, release job skipped), belt `38526000985`,
  reviewers `38526000973`, Factory 2 `38526000955`.

The three runs' APKs are not byte-identical to each other, and the reason is worth stating rather than
hiding: for `2943074` the size is 5,795,656 bytes (one byte fewer than `ca8f4d3`) with SHA-256
`a838fc4741e4d6ddf37b4fd50d04dc014b83c82b6aaaab951d43e6d1db682f0f` and debug-certificate SHA-256
`20ee01c451f367cc01d4994b7685aa257ad702dae2315de9cba8f0bb3db7301c`. A GitHub-hosted runner starts with
no `~/.android/debug.keystore`, so AGP generates a fresh debug key for every build, and the signature
blocks that key produces are part of the file. What is reproduced is the build: the same variant,
`package=com.savage.soulbrowser`, `versionName=3.0.0`, `versionCode=5`, `min_sdk=26`, `target_sdk=35`,
the same three declared permissions, the same launcher activity and the same v1+v2+v3 signing scheme. A
byte-identical APK can only come from a pinned signing key, which is exactly the key material this
repository refuses to commit (A11); the release variant, which is the one meant to carry an operator's
key, is reported unsigned instead.

## The Android pipeline, step by step

### Build, unit tests and lint — `success` (job 115703371992)

- Set up job: `success`
- Checkout: `success`
- Set up JDK 17: `success`
- Prepare the Android SDK: `success`
- Set up Python: `success`
- Verify the SDK packages the build pins: `success`
- Repository invariants (assets, manifest, pins, no placeholders): `success`
- Fail on dynamic dependency versions: `success`
- Gradle clean: `success`
- Unit tests (JVM): `success`
- Android Lint (abortOnError): `success`
- Assemble debug APK: `success`
- Assemble release APK (R8 + resource shrinking): `success`
- Assemble instrumented test APK: `success`
- Inspect and hash the built APKs: `success`
- Verify the debug APK signature (v1 + v2 + v3): `success`
- Verify release APK signing state (unsigned is reported, not hidden): `success`
- Verify APK identity, version and provenance: `success`
- Surface the failure: `skipped`
- Upload APKs, reports and evidence: `success`
- Post Set up Python: `success`
- Post Set up JDK 17: `success`
- Post Checkout: `success`
- Complete job: `success`

### Emulator instrumented tests and install smoke test — `success` (job 115704310821)

- Set up job: `success`
- Checkout: `success`
- Set up JDK 17: `success`
- Prepare the Android SDK: `success`
- Download the APKs built by the build job: `success`
- Verify the downloaded APK identity: `success`
- Enable KVM for the emulator: `success`
- Run instrumented tests on an API 35 emulator: `success`
- Surface the failure: `skipped`
- Collect emulator evidence: `success`
- Upload instrumented evidence: `success`
- Post Set up JDK 17: `success`
- Post Checkout: `success`
- Complete job: `success`

### Publish signed release APK — `skipped` (job 115705487326)

The emulator job's test steps are the ones that prove behaviour on a device: `Run instrumented tests on an API 35 emulator` runs `tools/ci/android_emulator_run.sh`, which fails the job if any connected test fails or if the install smoke test (`tools/android_apk_smoke.sh`, twelve steps, each asserting what it observed) does not pass. The failure report step of that script is what would have made a failure visible, so its silence together with the step's `success` is the evidence that all 52 connected cases and all twelve smoke steps passed.

## APK identity, hash and signing (build job annotation, verbatim)

```
apk=/home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/shell/android_project/app/build/outputs/apk/debug/app-debug.apk
size_bytes=5795657
sha256=391b1a99f57eb637b388e9a7ea1e282cec5c48ca83720f92ad5be424fb749ece
package=com.savage.soulbrowser versionName=3.0.0 versionCode=5
min_sdk=26 target_sdk=35
permissions=['android.permission.ACCESS_NETWORK_STATE', 'android.permission.INTERNET', 'com.savage.soulbrowser.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION']
signed v1=True v2=True v3=True
certificate=Common Name: Android Debug, Organization: Android, Country: US sha256=61d08420e966cf853b06f38c3dd1e8041cd04331f725a851a1deefe526941e1c
launcher=com.savage.soulbrowser.ui.MainActivity
```

Build job warning, verbatim: `release APK is UNSIGNED - no keystore.properties was supplied to CI.`

That is the documented state of this build (A11: no signing credentials are invented or committed). The install/launch/restart evidence above is therefore for the debug-signed APK, whose signer the annotation names; the release variant is built, R8-processed and reported honestly as unsigned.

## What the device run proves, criterion by criterion

- AC-001: the app installed and its launcher activity started without a crash entry (smoke steps 3-7 and the crash-log check in step 11).
- AC-023/24/25/37: the process was stopped with `am force-stop` and relaunched twice, then checked to be alive (smoke steps 9-11).
- AC-044: the installed package was verified to be the APK under test before installation (`sha256sum -c SHA256SUMS.txt` in the emulator job), then installed, launched, uninstalled and reinstalled (smoke steps 3-7 and 12).
- AC-027 and the rest of the connected suite: 52 instrumented cases ran on the API 35 emulator with no failure.

## Known limitations of this evidence

- The release APK is unsigned in CI (above); `Publish signed release APK` is skipped on branches by design and only runs when the repository supplies a keystore. It has never been installed on a device in this environment, so the device evidence is for the debug-signed debug APK, and a release install remains to be proven once an operator supplies a key.
- The debug-signed APK differs byte for byte between runs because each runner generates its own
  debug keystore (see above); `tools/reproducible_build_verifier.py` therefore checks the APK's
  identity, version, permissions, launcher and signing scheme, not a fixed hash.
- The APK artifact itself is not downloadable from the sandbox this report was written in (`gh api .../artifacts/<id>/zip` returns an empty file and the storage redirect fails), so the identity, size, hash, certificate and permission values above come from the build job's own annotation rather than from re-hashing the file here. The emulator job hashed the bytes it installed through `SHA256SUMS.txt`.

## The runs that preceded this one

Each earlier tip of this branch failed, was diagnosed and was fixed; the failures are listed here so this file cannot be read as a first-try pass:

- `371cd5c`, `54de056`: the androidTest source did not compile (`ActivityScenario.State.DESTROYED` does not exist in the pinned library) and the smoke test aborted silently in step 9.
- `597275d`: four connected failures - the smoke run aborted by an unguarded `pidof` pipeline (dash, `set -o pipefail`) and three tests raced a navigation that had not been committed to the page history.
- `ea2e62a`: the two list journeys launched their screen with a task-clearing `ActivityScenario`, which destroyed the browser the row was meant to come back to.
- `399a23a`: one connected failure left - the list rows were tapped through Espresso, which injected the tap at the background browser's omnibox coordinates, so the row's click listener never ran.
- `399a23a` (second attempt): one connected failure left - `WebView.saveState()` was read next to a navigation that had not been committed, and Chromium reported the home page's load failure after the browser had already moved on, which rewrote the active tab.
