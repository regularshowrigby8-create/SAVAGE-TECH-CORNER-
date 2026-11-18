# nova-tv build diagnostics

Written automatically by the "nova-tv Android" workflow.

Run: 44346432667  Commit: 5951d82

## result

**success.** `nova-tv-debug-apk`, 15,190,089 bytes.

This is the first green build of the final UI. The previous APK was 15,089,428
bytes; the difference is the new UI and player (about 100 kB of compiled code and
resources).

## the two failures that preceded it

Both were recorded here by CI and both were read late, which is the only real
lesson in this file.

**Run 44344506757 -- 60 errors, two causes.**
- 47 were one missing import: `PlayerSurface.kt` used `NovaPlayer`,
  `PlayerTimings` and `SkipSeconds` without importing them. Every "unresolved
  reference" and the delegate errors were symptoms of a type that never resolved.
- 13 were the `getValue`/`setValue` operator imports required by
  `var x by remember`.

**Run 44345642494 -- 5 errors, three causes.**
- A trailing lambda binds to the *last* parameter. `PrimaryButton(label, onClick,
  modifier)` therefore sent `{ ... }` into `modifier`.
- `NovaApp.kt` was still missing the delegate operator imports.
- `stream.name` inside `ifBlank` failed inference because the receiver was
  shadowed.

## why this file exists

Before it, a failed build was a red X and an unreadable log. Everything above was
diagnosed by guessing, and the guesses produced two wasted pushes. The workflow
now writes the compiler errors to a tracked file, and reading it first turns a
multi-round-elimination exercise into a five-minute fix.

Read this file before editing Kotlin. It is written on every run and was ignored
on every run until run 44345642494.
