# nova-tv build diagnostics

Written automatically by the "nova-tv Android" workflow, but not written by it
for this run -- see below.

Run: 44352545863  Commit: d860323

## result

**success.** Every step green.

| Artefact | Bytes |
|---|---|
| `nova-tv-release-apk` | 1,835,124 |
| `nova-tv-debug-apk` | 15,190,091 |

This file is normally overwritten by CI *only when a build fails*. It is
committed here after a successful run so it stops describing a failure that was
fixed four commits earlier -- which is what it was doing.

## the five failures that preceded this

**Run 44344506757 -- 60 compile errors.**
- 47 were one missing import (`NovaPlayer`, `PlayerTimings`, `SkipSeconds` in
  `PlayerSurface.kt`).
- 13 were the `getValue`/`setValue` operator imports required by `by remember`.

**Run 44345642494 -- 5 compile errors.**
- A trailing lambda binds to the last parameter, so
  `PrimaryButton(label, onClick, modifier)` sent `{ ... }` into `modifier`.
- `NovaApp.kt` was missing the delegate imports again.
- `stream.name` inside `ifBlank` failed inference with a shadowed receiver.

**Run 44348703491 -- `compileSdkVersion is not specified`.**
A splice from the signing block to `buildTypes {` deleted `compileSdk` and the
whole `defaultConfig`. Purely my edit; the Kotlin was fine.

**Runs 44349312903, 44350409819 -- release failed, no diagnostics.**
The diagnostics step was gated on `failure()` but positioned *before* the release
step. `failure()` only becomes true after something has already failed, so on
every run where release failed later, the step had already been skipped. Three
runs produced no usable evidence because a guard that could not report looked
exactly like a guard that had nothing to say.

**Run 44351443731 -- release failed inside lint.**
`lintVitalRelease` crashed in `NonNullableMutableLiveDataDetector` with
`IncompatibleClassChangeError`: lifecycle's lint jar is built against a Kotlin
Analysis API where `KaCallableMemberCall` is an interface, and AGP 8.7.3 bundles
one where it is a class. Known AGP 8.7.x incompatibility. `app/lint.xml` disables
that single detector, which has no input here because the project contains no
LiveData.

## read this file first

Before editing Kotlin or Gradle in this repository: if CI is red, the answer is
in this file. It took five rounds to learn that, and four of the five were
diagnosable from a file that was already being written.
