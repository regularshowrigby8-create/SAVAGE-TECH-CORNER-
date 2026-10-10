# Shell Module Changes Ledger

## Directly Verified Changes (2026-10-10)

- Clarified module architectural role: Implements Soul browser-style shell architecture for the Savage Browser application.
- `shell/android_project/AndroidManifest.xml`: Configured application label to `Savage Browser` with API 35 target and API 26 minimum SDK.
- `shell/soul_browser/browser_shell.py`: Implemented `WebViewHolder`, `WebViewBridge`, and `SoulBrowserShell` with handlers for `btn_back`, `btn_forward`, `btn_refresh`, `btn_home`, `btn_menu`, and `btn_tabs`.
- `shell/tests/test_browser_shell.py`: 5 unit tests directly verified passing (navigation, history, bridge invocation, and dead-button fail-closed behavior).

## Directly Verified Changes (2026-10-11)

- `shell/android_project/AndroidManifest.xml`: Added `android:allowBackup="false"` so app data is not exposed through device backups.
- `shell/android_project/res/xml/network_security_config.xml`: Removed an empty `<pin-set>` that claimed certificate pinning while configuring no pins; a comment now states that pins are still to be added.
- Known gap (not fixed): the Gradle files do not match the source layout, so the project cannot be built with `./gradlew assembleDebug` yet.
- Direct verification: both XML files parse cleanly; `python3 test_entire_system.py` and `bash .claude/scripts/gates.sh` pass.
