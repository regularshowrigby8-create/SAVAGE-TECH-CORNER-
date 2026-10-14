# CI evidence — commit 804571e (round 12)

Branch `arena/5223ef6f-savage-tech-corner`, commit `804571e9ed59c9f6f174bd312cfb9760bcb21b92`
(`fix(factory): make sealing reproducible and keep the payloads with the seal`).

Every job below ran on this commit. Nothing here is copied from an earlier commit's run, and the
runs that failed on the two previous commits are listed in §3 with what they failed on.

## 1. Runs

| Workflow                             | Trigger      | Run                                                                                                     | Conclusion |
| ------------------------------------ | ------------ | ------------------------------------------------------------------------------------------------------- | ---------- |
| Comprehensive Quality Conveyor Belt  | push         | [38586381793](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38586381793) | success    |
| Comprehensive Quality Conveyor Belt  | pull_request | [38586398608](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38586398608) | success    |
| Android Build, Test and Release      | pull_request | [38586397237](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38586397237) | success    |
| Factory 2 — Single Debug Job         | push         | [38586381824](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38586381824) | success    |
| Factory 2 — Single Debug Job         | pull_request | [38586397960](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38586397960) | success    |
| AI PR Code Reviewers (Dual Fallback) | pull_request | [38586396787](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/38586396787) | success    |

Check runs on the commit:

| Check                                                | Conclusion                                                           |
| ---------------------------------------------------- | -------------------------------------------------------------------- |
| Conveyor Belt Verification Pipeline (push)           | success                                                              |
| Conveyor Belt Verification Pipeline (pull_request)   | success                                                              |
| Build, unit tests and lint                           | success                                                              |
| Emulator instrumented tests and install smoke test   | success                                                              |
| AI PR Code Reviewers — Dual Automated Code Reviewers | success                                                              |
| Factory 2 — debug-agents (push)                      | success                                                              |
| Factory 2 — debug-agents (pull_request)              | success                                                              |
| Publish signed release APK                           | skipped (no `keystore.properties` secret on this repository; see §4) |

## 2. Build job: the APK this commit produced

The `Build, unit tests and lint` job published its APK annotation verbatim:

```
notice | APK evidence | apk=…/shell/android_project/app/build/outputs/apk/debug/app-debug.apk
size_bytes=5795660
sha256=629baeb688174a9674f3a730e9738248e5f2d97478763530d26bc99ff180bdb4
package=com.savage.soulbrowser versionName=3.0.0 versionCode=5
min_sdk=26 target_sdk=35
permissions=['android.permission.ACCESS_NETWORK_STATE', 'android.permission.INTERNET', 'com.savage.soulbrowser.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION']
signed v1=True v2=True v3=True
certificate=Common Name: Android Debug, Organization: Android, Country: US sha256=111de1d1fceb2108aba6e75e816aed82f2696d2b43937d0c65b31509f7118964
launcher=com.savage.soulbrowser.ui.MainActivity
```

Job artifacts of that run:

| Artifact             | Bytes     |
| -------------------- | --------- |
| `savage-browser-apk` | 5,793,194 |
| `emulator-evidence`  | 480,436   |

The APK annotation's `size_bytes` (5,795,660) is the file itself; the artifact archive is the
5,793,194 bytes above. This is the debug variant on the app identity the manifest declares,
`min_sdk=26`/`target_sdk=35`, the two declared permissions plus the Android-generated
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, signed with the CI debug key (v1+v2+v3) — the same
variant and identity AC-044…AC-047 describe, produced by this commit's own checkout.

**The emulator leg passed.** On `226b71c` the `Emulator instrumented tests and install smoke test`
job failed once and could not be root-caused from the logs available to this session (recorded in
`artifacts/reports/sealed-factory-stage-6afc67a.md` §6). On this commit the same job passed, with its
`emulator-evidence` artifact published. That is a later observation, not a claimed fix: no change in
this commit targets that job.

## 3. The two commits before this one, and why they are red

| Commit    | What failed                                                           | Cause                                                                                                                                                                                                                                                                                 | Fixed by                |
| --------- | --------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------- |
| `9c4c1b1` | Code Quality & Linting                                                | eslint: a template literal with no interpolation in `scripts/conveyor_belt.js`; prettier: the two import registries were written with the vendoring script's own indentation                                                                                                          | `2621e37`               |
| `2621e37` | gates → Factory 2 `run_all.sh` (`FACTORY 2 RESULT: FAIL`)             | factory-2's own security agent read two `shell=True` call sites in the sealed runtime (`insecure_subprocess_shell`) and a realistic credential literal in a new test fixture (`high_entropy_secret`); its critic blocked                                                              | `6c904cc`               |
| `6c904cc` | Conveyor Belt Verification Pipeline → station 0, then Gates 10 and 11 | the seal could not verify in a clean checkout: `tarfile`'s gzip wrapper stamps the current time into the gzip header, so re-sealing rewrote all 137 payloads while keeping their sizes, and the commit carried a `SEAL.json` from the latest seal beside payloads from an earlier one | `804571e`               |
| `804571e` | —                                                                     | —                                                                                                                                                                                                                                                                                     | all six workflows green |

No check was skipped, disabled, marked `continue-on-error`, or made unconditional to get here: the
eslint rule, the security agent's entropy detector, the seal verifier and station 0 all still fail on
the inputs that made them fail.

## 4. The one job that does not run, and why

`Publish signed release APK` is **skipped**, which is what it is configured to do when
`keystore.properties` is absent. This repository has no release-keystore secret, so the release
variant (AC-046's signed-release leg) cannot be produced in CI from this checkout. The debug APK
above is signed with the runners' debug key, which is not a release signing key: nothing in this
round claims a release APK exists. See AC-046 and the ambiguity log entry A11 in
`docs/ACCEPTANCE_CRITERIA.md`.
