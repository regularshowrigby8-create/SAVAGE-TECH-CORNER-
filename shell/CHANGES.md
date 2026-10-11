# Shell Module Changes Ledger

## Directly Verified Changes (2026-10-11 — Modern Lighting Shell UI 2.0 Release Build)

- **Modern Lighting Shell UI 2.0 (`shell/soul_browser/browser_shell.py`)**:
  - Upgraded `ModernLightingUITheme` to `ModernLightingGlass2` with OLED dark canvas (`#090D16`), Cyber Cyan glow accents (`#06B6D4`), electric indigo badges (`#6366F1`), and 24px backdrop blur.
  - Added `btn_ai_assistant` to core lighting navigation dock and added `action_ai_assistant()` handler managing smart summary and Q&A drawers.
  - Added `getUIVersion` bridge contract returning `2.0.0-lighting-modern` while strictly preserving `getAppVersion` backwards-compatibility.
  - Rebuilt Android layout `shell/android_project/app/src/main/res/layout/activity_main.xml` with translucent AppBar, glass omnibox capsule, embedded AI assistant trigger, dynamic progress bar, and floating bottom navigation dock with tint accents.
  - Upgraded unit test suite `shell/tests/test_browser_shell.py` with AI assistant drawer toggle and Modern UI 2.0 verification (all 14 shell tests PASS).

## Directly Verified Changes (2026-10-11 — Modern Lighting Shell UI)

- **Modern Lighting UI Rebuild**:
  - Rebuilt `shell/soul_browser/browser_shell.py` with Modern Lighting Glass design tokens (`ModernLightingUITheme`), glassmorphism floating bottom dock, dynamic progress indicators, and verified navigation states (`can_go_back`, `can_go_forward`).
  - Added modern Android layout resource `shell/android_project/app/src/main/res/layout/activity_main.xml` with CoordinatorLayout, translucent AppBar, security badge indicator, smooth progress bar, and floating glass dock with tab counter capsule.
  - Retained strict backwards-compatibility with fundamental contracts: `WebViewBridge`, `WebViewHolder`, and all core UI actions (`btn_back`, `btn_forward`, `btn_refresh`, `btn_home`, `btn_menu`, `btn_tabs`).
  - Added `render_modern_ui_state()` API returning complete reactive UI hierarchy.

## Directly Verified Changes (2026-10-11 — Android Gradle & Security Hardening)

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
