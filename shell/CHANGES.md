# Shell Module Changes Ledger

## Directly Verified Changes (2026-10-11)

- **Android Gradle Layout Standardized**:
  - Restructured `shell/android_project/app/src/main/` standard directory structure.
  - Placed `MainActivity.java` into `shell/android_project/app/src/main/java/com/savage/soulbrowser/MainActivity.java`.
  - Placed `AndroidManifest.xml` into `shell/android_project/app/src/main/AndroidManifest.xml`.
  - Placed `network_security_config.xml` into `shell/android_project/app/src/main/res/xml/network_security_config.xml`.
  - Retained backward-compatible root files for existing markdown documentation links.
- **Modernized Android Lifecycle & Navigation**:
  - Replaced deprecated `Activity.onBackPressed()` with modern AndroidX `OnBackPressedCallback` attached via `getOnBackPressedDispatcher()`.
- **Hardened WebView Bridge**:
  - Refactored `SecureSoulBridge` to a `public static class` holding `WeakReference<MainActivity>` and `WeakReference<WebView>` to eliminate Activity memory leaks and attack surface via `addJavascriptInterface`.
- **Unicode-Preserving Input Whitelist Validation**:
  - Replaced lossy ASCII-only regex character-stripping with Unicode character class pattern `^[\p{L}\p{N}\s._!?,@#&()-]{1,500}$`, preserving legitimate accented and international scripts while rejecting script injection payloads.
- **Fixed Network Security Config**:
  - Removed empty `<pin-set>` placeholder from `network_security_config.xml` to prevent undefined pinning failure on Android runtime.
- **Android Unit Test Coverage Added**:
  - Added JUnit test suite `shell/android_project/app/src/test/java/com/savage/soulbrowser/MainActivityTest.java` testing scheme whitelisting, cleartext rejection, origin privileges, and Unicode input validation.
- **Android Shell CI Workflow Added**:
  - Created `.github/workflows/android-build.yml` executing `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` on push and PR.

## Directly Verified Changes (2026-10-10)

- Clarified module architectural role: Implements Soul browser-style shell architecture for the Savage Browser application.
- `shell/android_project/AndroidManifest.xml`: Configured application label to `Savage Browser` with API 35 target and API 26 minimum SDK.
- `shell/soul_browser/browser_shell.py`: Implemented `WebViewHolder`, `WebViewBridge`, and `SoulBrowserShell` with handlers for `btn_back`, `btn_forward`, `btn_refresh`, `btn_home`, `btn_menu`, and `btn_tabs`.
- `shell/tests/test_browser_shell.py`: 5 unit tests directly verified passing (navigation, history, bridge invocation, and dead-button fail-closed behavior).
