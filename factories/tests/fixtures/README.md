# Test Fixtures — Deliberately Corrupted Canary (NOT a bug)

`corrupted_stock_classes.dex` inside this directory is **intentionally broken**.

It is the exact `classes.dex` extracted from the legacy stale `assets/SavageBrowser-v2.0.0.apk`
(git commit `d487ac9`) — the artifact that caused the recurring on-device launcher crash
(`ClassNotFoundException`): its header `file_size` does not match the real length, its map list
is corrupt, and only 5/175 class definitions resolve to sane descriptors.

It is pinned here as a **regression canary**: `test_factories_pipeline.py` asserts that
`verify_dex_structure()` keeps rejecting these exact bytes. If that test ever passes on this
fixture, the DEX structural gate has been silently weakened and the same crash-loop class of
ground-up APK corruption could be certified again, unnoticed.

Do **not** "fix", replace, or delete this file — its corruption is the test.
