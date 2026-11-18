# nova-tv — build status

**The app compiles. A real debug APK is produced by CI.**

Workflow: `nova-tv Android` (`.github/workflows/nova-tv-android.yml`)
Run: **44333505082** — `assembleDebug` **success**, `testDebugUnitTest` **success**
Artefact: **`nova-tv-debug-apk`, 15,089,426 bytes**

This is the first time nova-tv has ever been compiled. Before this run the
Kotlin had never been through a compiler, so nothing about it was known to be
valid — the structural linter in `ops/kotlin_lint.py` proves layout, not
correctness.

## Toolchain

The stack was imported, not invented, and every version is recorded with the
project it was read from.

| Component | Version | Taken from |
|---|---|---|
| Gradle | 8.9 | vipulasri/Timeline-View (Apache-2.0) |
| AGP | 8.7.3 | same |
| Kotlin | 2.0.21 | same |
| compileSdk / targetSdk | 35 | required by the resolved dependencies |
| Compose BOM | 2024.12.01 | gonzaroman/acornix (MIT) |
| tv-material | 1.1.0 | android/compose-samples (Google) |
| tv-foundation | 1.0.0-alpha11 | rommapp/argosy-launcher |
| media3 | 1.5.1 | horsemail/yourtv, Gurupreet/ComposeCookBook |
| OkHttp | 4.12.0 | gonzaroman/acornix |

Rejected on licensing, not quality: `damontecres/Wholphin` (GPL-2.0) and
`DeadWaveWave/demo2apk`, the highest-starred "vibe coded Android app" repo,
which ships **no licence at all**.

## Every failure, and what it actually was

Each of these was a real defect in nova-tv, found only by compiling. None would
have been caught by the linter.

| # | Failure | Cause |
|---|---|---|
| 1 | 40 AAR metadata errors | Resolver pulled compose ui 1.10.3 / lifecycle 2.9.4, which demand AGP ≥ 8.6 and compileSdk ≥ 35. Stack was AGP 8.5.2 / 34. |
| 2 | `AAPT: resource drawable/app_banner not found` | The manifest referenced `app_banner` and `ic_launcher`; **neither existed**. Imported from this repo's own `tv/android-tv`. |
| 3 | 18 Kotlin errors | `items` imported from two packages, making the call ambiguous; `return@async emptyList()` untyped. |
| 4 | `Unresolved reference: lazy` | `androidx.tv.foundation.lazy.list.*` is not in tv-foundation 1.0.0. |
| 5 | `Could not find androidx.tv:tv-foundation:1.1.0` | 1.1.0 is not published as a release. |
| 6 | 9 Kotlin errors | `items(Int)` bound instead of `items(List)`; `runCatching`'s `T` unconstrained. |

Two of my own diagnoses were wrong on the way and are recorded so they are not
repeated: tv-foundation 1.1.0 does **not** exist, and `TvLazyColumn` is **not**
in tv-material — it is in `androidx.tv.foundation.lazy.list`, available from
`1.0.0-alpha11`.

## What is still not proven

- **The APK has never been installed or run.** It compiles; that is all that is
  known. No emulator, no device.
- **Only `assembleDebug`.** `assembleRelease` (R8 shrinking) is untested.
- **Signing is untouched.** The debug APK is signed with the throwaway debug key
  and is not distributable. See `docs/BUILD-FACTORY-SDK-PLAN.md`.
- The unit tests that ran are the JVM ones (`PlayerStateTest`, `UrlGuardTest`).

## Reproducing

Push to any `arena/*` branch touching `nova-tv/android/**`, or run the workflow
manually. The APK is attached to the run as `nova-tv-debug-apk`.
