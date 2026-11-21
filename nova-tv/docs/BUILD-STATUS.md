# nova-tv — build status

## Current (nova-tv-v2, local build)

One module, two product flavors (`device` dimension):

| Flavor | applicationId | APK | Bytes |
|---|---|---|---|
| `tv` | `dev.nova.tv` | `apk/tv/release/app-tv-release.apk` | 8,559,588 |
| `mobile` | `dev.nova.mobile` | `apk/mobile/release/app-mobile-release.apk` | 8,885,347 |

`./gradlew :app:testTvDebugUnitTest :app:testMobileDebugUnitTest :app:assembleRelease`
— BUILD SUCCESSFUL (measured 2026-11-20).
107 unit tests per flavor (214 total), 0 failures, 0 errors. `lintVitalRelease`
runs inside `assembleRelease` and passed; the full `lintTvDebug` / `lintMobileDebug`
gate passes in CI, where an error fails the job.
Both release APKs signed (`apksigner verify` passes). Different package names,
so both install side by side. Neither has been installed on a device from this
environment.

### Why the APK is ~8.5 MB and not 20–30 MB

Measured from the APK itself: ~5.9 MB is native code (the Media3 FFmpeg audio
decoder, `libffmpegJNI.so`, ~1.4 MB for each of 4 ABIs), ~3.9 MB is dex after
R8, ~0.5 MB resources. R8 minify + resource shrinking are on for release.
Size is not a quality signal. Larger players usually bundle things Nova
deliberately does not: a torrent/streaming server (Nova plays direct HTTP
only, no torrents), a second player engine such as libVLC/libmpv, analytics
and crash SDKs, or unminified builds. Splitting by ABI would make each
device's download several MB smaller still.

## Latest CI run (nova-tv-v2)

Workflow: `nova-tv Android` (`.github/workflows/nova-tv-android.yml`), which now
also triggers on `nova-tv-v2`, so the branch builds itself.
Run: [44847977271](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/44847977271)
(commit `c7c8042`) — every step green (build, both unit suites, lint, R8 release,
APK verification).

| Artefact | zipped | sha256 |
|---|---|---|
| `nova-tv-release-apk` | 5.01 MB | `145bff10d8a7c3f84712c95ab9c1bdd3ce00faf3776cd678c3441d849f990a66` |
| `nova-mobile-release-apk` | 5.3 MB | `b47b36f780b5f27bec241abaee889910220d8118b59700782877fd02b5b20865` |
| `nova-tv-debug-apk` | 37 MB | `53d6010f30935d82cfe897f06e7b8370eb862e0cab9f13c2c0713d4d18c841f4` |

The committed `artifacts/*.apk` files are still the pre-debug-pass binaries; the
freshly built ones are the CI artefacts above. Downloading them needs a signed-in
GitHub session.

## Earlier CI run (v1)

**The build is finalised: both variants compile, are signed, and are verified.**

Workflow: `nova-tv Android` (`.github/workflows/nova-tv-android.yml`)
Run: **44352545863** — every step green.

| Artefact | Bytes | Variant |
|---|---|---|
| `nova-tv-release-apk` | **1,835,124** | R8-minified, shrunk, signed |
| `nova-tv-debug-apk` | 15,190,091 | debuggable, unminified |

The release APK is **12% the size** of the debug APK. That ratio is the whole
point of the release variant: Compose, Media3 and Kotlin ship a great deal of
code that an app never calls, and R8 removes it.

## What "finalised" means here, precisely

Every statement below is backed by a step that ran in run 44352545863.

| Claim | How it is proven |
|---|---|
| The Kotlin compiles | `assembleDebug` and `assembleRelease` both succeed |
| Tests pass | `testDebugUnitTest` succeeds |
| Lint passes | `lintDebug` succeeds; errors would have failed the job |
| R8 shrinking is safe | `assembleRelease` completes with full-mode shrinking |
| The APK is a real package | `aapt2 dump badging` reads both |
| The APK is signed | `apksigner verify --print-certs` passes both |
| The APK is aligned | `zipalign -c -v 4` passes both |

**What is still not proven, and cannot be from here:** the APK has never been
installed on a device or an emulator. Everything above shows the artefact is
well-formed and installable. None of it shows that the app runs, that a
catalogue loads, or that a video plays. That requires a device.

## Toolchain

The stack was imported, not invented, and every version is recorded with the
project it was read from.

| Component | Version | Taken from |
|---|---|---|
| AGP | 8.13.0 | raised from 8.7.3 so Compose 1.10 / lifecycle lint jars run (see below) |
| Gradle | 8.13 | required by AGP 8.13 |
| Kotlin | 2.0.21 | pairs with Compose BOM 2024.12.01 |
| compileSdk / targetSdk | 35 | |
| minSdk | 26 | |
| tv-material | 1.1.0 | `android/compose-samples` |
| tv-foundation | 1.0.0-alpha11 | the only published version |
| media3 | 1.9.0 (+ Jellyfin FFmpeg 1.9.0+1) | ARVIO's player stack |
| lifecycle | 2.8.7 | |
| coil-compose | 2.7.0 | artwork, shares Nova's OkHttp client |
| namespace | `dev.nova.tv` | |

## Signing

The release variant is signed with a **development key** generated on first
build from fixed inputs, so CI and a local build produce the same installable
artefact.

- The keystore is **never committed**. `nova-tv/android/keystore/` is in
  `.gitignore`.
- Override with `NOVA_KEYSTORE_PASSWORD` and `NOVA_KEY_ALIAS`, or by placing
  `keystore.properties` in that directory.
- Generation is a real Gradle task (`ensureNovaKeystore`) that
  `validateSigningRelease` and `packageRelease` depend on. Generating it in
  `doFirst` was wrong: AGP consumes the signing config during task setup, so the
  key could arrive after packaging had already decided the file was missing.

**Outstanding before publishing:** this is a reproducible development key with a
published password. It is fine for internal installs and sideloading. It is not
fine for a store listing. Replace it with an uploaded keystore and rotate the
artefact identity before any public release.

## The five failures it took to get here

Recorded because each one cost a CI round, and four of the five were avoidable.

| # | Failure | Cause |
|---|---|---|
| 1 | 60 compile errors | One missing import in `PlayerSurface.kt` caused ~30 of them; the rest were `getValue`/`setValue` operator imports. |
| 2 | 5 compile errors | A trailing lambda binds to the *last* parameter, so `PrimaryButton(label, onClick, modifier)` sent the lambda into `modifier`. |
| 3 | `compileSdkVersion is not specified` | My own splice from the signing block to `buildTypes {` deleted `compileSdk` and `defaultConfig`. |
| 4 | Release failed, no diagnostics | The diagnostics step was gated on `failure()` but sat *before* the release step, so it was skipped on every run. |
| 5 | Release failed inside lint | `lintVitalRelease` crashed in `NonNullableMutableLiveDataDetector` — a known AGP 8.7.x / lifecycle lint-jar incompatibility, not a nova-tv defect. |

Two guards came out of this and both now run before CI:

- `ops/kotlin_lint.py` requires the module to declare `namespace`, `compileSdk`,
  `defaultConfig`, `applicationId`, `minSdk`, `targetSdk`, `versionCode`,
  `versionName` and `buildTypes`.
- `tests/test_buildfiles.py` pins that with four fixtures, including the exact
  deletions that caused failure 3.

The repeating lesson across failures 1, 2 and 4 is the same: **a guard that
cannot report is worse than no guard**, because it looks like coverage. The
diagnostics step spent three runs being skipped while the file it writes showed
an error from a run two days old.

## The one lint suppression, and why it is not cheating

`app/lint.xml` disables exactly one detector: `NullSafeMutableLiveData`.

The detector looks for nullable `MutableLiveData` observed unsafely. This project
contains **no LiveData at all** — it uses `StateFlow` and
`collectAsStateWithLifecycle` throughout. Verified by grep: zero hits. The
detector has no input, so disabling it loses no coverage. It was blocking the
release because `assembleRelease` runs `lintVitalRelease` and the detector
crashes inside lint itself with `IncompatibleClassChangeError`.

`checkReleaseBuilds` and `abortOnError` remain **on**, so every other check still
gates the release. The file records the AGP upgrade that would make the entry
unnecessary, and instructs its deletion at that point.

## Imported versus hand-written

This app is **not** assembled from imported components. One file is imported and
reproducibly generated:

```
ImportedTimings.kt  <- tv/zeroplay-debrid/lib/player-core.js
                    via scripts/import_player_core.py
```

Re-running the importer reproduces it byte-for-byte. Everything else in `src/`
is hand-written against the Stremio addon protocol, plus 25 library
dependencies. `APP-COMPONENT-PLAN.md` proposed five components to be written by
hand; the final count was 22 Kotlin files. That is worth knowing before the next
phase: the licence wall (every Kotlin media client in the reference set is
GPL-3.0) makes hand-writing hard to avoid, but "imported from open source
projects" overstates what actually happened.

## Reproducing locally

```bash
cd nova-tv/android
./gradlew :app:assembleDebug      # unminified, installable
./gradlew :app:assembleRelease    # R8 + signed
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
python3 ../ops/kotlin_lint.py     # structural + build-file guard
```

## Lint toolchain (2026-11-19)

tv-material 1.1.0 resolves Compose to 1.10.x. Its lint jars (and lifecycle's)
are built for a newer Kotlin Analysis API than AGP 8.7.3 bundles, so detectors
such as `RememberInComposition`, `FrequentlyChangingValue` and
`AutoboxingStateCreation` died with `IncompatibleClassChangeError` and aborted
lint. Exempting them one at a time was whack-a-mole, so AGP moved to 8.13.0
(Gradle 8.13). Every detector now runs and `app/lint.xml` has no exemptions.
The newer lint found real issues, which were fixed: `windowLayoutInDisplayCutoutMode`
moved to `values-v27`, and media3 `@UnstableApi` call sites are opted in.
