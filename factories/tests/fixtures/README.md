# Test Fixtures — Verification Canary Bytes (NOT bugs)

No APK binary is tracked in this repository ("delete all APK versions, keep the
architecture"). The verification _architecture_ is retained, so the raw bytes the
checks operate on are pinned here as small fixtures instead of whole APKs.

## `corrupted_stock_classes.dex` — intentionally broken (do NOT fix)

Exact `classes.dex` extracted from the legacy stale `assets/SavageBrowser-v2.0.0.apk`
(git commit `d487ac9`) — the artifact that caused the recurring on-device launcher
crash (`ClassNotFoundException`): header `file_size` mismatches the real length, the
map list is corrupt, and only 5/175 class definitions resolve to sane descriptors.

Pinned as a **regression canary**: tests assert `verify_dex_structure()` keeps
rejecting these exact bytes. If a test ever passes on this fixture, the DEX
structural gate has been silently weakened.

## `valid_classes.dex` — positive control

A structurally valid `classes.dex` extracted from the (since-removed) tracked build
artifact. Tests assert `verify_dex_structure()` keeps _accepting_ these bytes, so the
gate's accepting path stays covered without any tracked APK.

## `real_v2_signing_block.bin` — signature-block positive control

The real 4096-byte APK Signing Block (v2/v3, magic `APK Sig Block 42`) extracted from
the former tracked artifact before deletion. Tests graft it onto a minimal synthetic
ZIP so `_has_v2_signing_block()` and signature checks can be exercised for both the
positive and negative case without tracking any large APK.

Do **not** replace or delete these files — they are the test inputs that keep the
binary verification gates honest after the APK binaries were removed.
