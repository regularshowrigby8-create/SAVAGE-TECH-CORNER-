# Shell Module Changes Ledger

## Directly Verified Changes (2026-10-10)

- Clarified module architectural role: Implements Soul browser-style shell architecture for the Savage Browser application.
- `shell/android_project/AndroidManifest.xml`: Configured application label to `Savage Browser` with API 35 target and API 26 minimum SDK.
- `shell/soul_browser/browser_shell.py`: Implemented `WebViewHolder`, `WebViewBridge`, and `SoulBrowserShell` with handlers for `btn_back`, `btn_forward`, `btn_refresh`, `btn_home`, `btn_menu`, and `btn_tabs`.
- `shell/tests/test_browser_shell.py`: 5 unit tests directly verified passing (navigation, history, bridge invocation, and dead-button fail-closed behavior).
