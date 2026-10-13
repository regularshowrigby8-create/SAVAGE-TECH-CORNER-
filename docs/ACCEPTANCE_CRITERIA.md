# Savage Browser — Acceptance Criteria (authoritative for the Android deliverable)

Every acceptance criterion below is executable: `Test method` names a real test (JVM unit test,
instrumented test, or a shell/Gradle command) that is present in this repository.

---

## 0. Brief resolution and recorded ambiguities

The implementation brief supplied to this session contained **unfilled placeholders**
(`[APP NAME]`, `[WHAT THE APP MUST DO]`, `[MIN SDK]`, `[COM.APP.PACKAGE]`, …). It also asked for
`python3 SAVAGE_FACTORY.py --root . gate`, but no `SAVAGE_FACTORY.py` existed at the repository
root (the factory shipped only as a ZIP payload).

Per the operating rule *"if a requirement is unclear, record the ambiguity and choose the safest
reasonable behavior; do not silently invent a major product decision"*, the brief was resolved
from the repository's own authoritative documents instead of being invented:

| Brief field | Resolved value | Source of truth |
|---|---|---|
| App name | **Savage Browser** | `README.md` §1, `docs/ARCHITECTURE.md` §1 |
| Goal | Security-hardened Android WebView browser for the Savage Tech Corner ecosystem | `README.md` §1, `docs/SECURITY.md` §1 |
| Target users | Privacy/security-conscious Android users of the Savage ecosystem | `docs/SECURITY.md`, `docs/ARCHITECTURE.md` §5 |
| Package / applicationId | `com.savage.soulbrowser` | `README.md`, `factory-3/tools/android_vm_verifier.py` |
| Minimum SDK | 26 (Android 8.0) | `README.md` ("API 26 to 35"), existing `app/build.gradle.kts` |
| Target SDK | 35 (Android 15) | `README.md` ("API 26 to 35"), `factory-3/tools/android_tools.py` |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE` only | existing manifest, `docs/ARCHITECTURE.md` §5 |
| Offline behaviour | Offline-resilient: local data served, clean offline UI, retry | `docs/ARCHITECTURE.md` §5.3 |
| Persisted data | Bookmarks + history in local SQLite, queued for later sync | `docs/ARCHITECTURE.md` §5.3 |
| Security invariants | HTTPS-only, fail-closed TLS, origin-gated JS bridge | `docs/SECURITY.md` §1 |

### Ambiguity log (decisions taken, with rationale)

| # | Ambiguity | Decision | Rationale |
|---|---|---|---|
| A1 | Brief placeholders | Resolved from repo docs (table above) | Rule 11; no major product decision invented |
| A2 | `SAVAGE_FACTORY.py` missing at root | Adopted the factory build that already ships in-repo (`SAVAGE_FACTORY_v4_DEBUG_FINAL.zip`, the hardened build) to `/SAVAGE_FACTORY.py` | Its own upload guide: "put this file at the root of your repository". V4-DEBUG is the build that fixes the review findings in `FACTORY_REVIEW.md` |
| A3 | Materialising the factory (`init`) into the product repo | **Not** materialised in-tree | `NO_PADDING.md` + `docs/factory/ENFORCEMENT.md` §4.4 require one canonical factory tree; `FACTORY_REVIEW.md` P2 #19 explicitly forbids adding a parallel factory. `init`+`doctor` were executed in an isolated scratch copy instead (evidence recorded) |
| A4 | "Required screens" (3) not specified | Browser, Bookmarks, History | Screens that the existing AppBar/dock controls and `docs/ARCHITECTURE.md` imply; each is a real Activity |
| A5 | Tabs button previously showed a `Toast` (fake success) | Implemented real tabs; multi-WebView sessions with a switcher dialog | Rule 5 (no fake success responses); a toast is not a feature |
| A6 | AppBar "AI assistant" button had no implementation | Removed; replaced by a working Bookmark (star) action | Rule 5; an unimplemented control is a dead control (`factory-2/criticism/dead_buttons_check.py` policy) |
| A7 | `activities.BrowserActivity` existed only as an unreferenced exported alias | Removed | An exported component with a `MAIN` filter and no launcher category is dead surface area; `android_vm_verifier.py` accepts `MainActivity` |
| A8 | Default search provider | DuckDuckGo (Google/Bing selectable) | Security/privacy posture of the product; user-selectable, persisted |
| A9 | Non-443 HTTPS ports | Allowed | Blocking them breaks ordinary sites; the security boundary is the scheme + TLS validation, not the port |
| A10 | Version bump | versionCode 5, versionName 3.0.0 | The rework changes package contents and behaviour; previous shipped value was 4 / 2.0.4 |
| A11 | Release signing credentials | Not invented. Release variant is configured for a real keystore supplied via `keystore.properties`/env; the verified artifact is the debug-signed debug APK | Rule 9 (never invent secrets) |
| A12 | `tools/vm_crash_and_install_debugger.py` claimed emulator/install results it cannot produce | Relabelled: it is a static APK structure analyser; real install/launch evidence now comes from the CI emulator job | Rule 3 (no invented emulator results) |
| A13 | `factory-3` APK "build" copied a pre-existing APK and byte-patched a signing block | Replaced with a real Gradle build, or an explicit failure when no Android toolchain exists | Rule 3/13; a copied APK is not a build |

---

## 1. Functional acceptance criteria

### AC-001 — Clean first launch
- **Feature**: F-01 Application start
- **Given**: a fresh install (no app data) on API 26+
- **When**: the launcher icon is tapped
- **Then**: `MainActivity` starts, the hardened WebView is created, the omnibox is focused-free and
  the home page URL is loaded; no crash and no permission dialog appears
- **Test method**: `MainActivityLaunchTest#firstLaunchShowsBrowserChrome`, CI step
  `adb shell am start -W -n com.savage.soulbrowser/.MainActivity`
- **Evidence required**: instrumentation test result XML + emulator `am start -W` output (TotalTime, Status: ok)

### AC-002 — Page navigation and history stack
- **Feature**: F-03 Navigation controls
- **Given**: the browser is on a loaded `https://` page and a previously loaded page exists
- **When**: the user taps Back, then Forward
- **Then**: the WebView returns to the previous entry and then to the next entry; the Back control is
  disabled when `canGoBack()` is false
- **Test method**: `NavigationStateTest#backAndForwardReflectWebViewHistory` (instrumented)
- **Evidence required**: instrumentation test result XML

### AC-003 — Reload, stop and home
- **Feature**: F-03 Navigation controls
- **Given**: a page is loaded
- **When**: the reload action is tapped while loading, then the home action is tapped
- **Then**: loading is stopped by the first tap, and the home URL is loaded by the second
- **Test method**: `NavigationStateTest#reloadStopsLoadingAndHomeResets`, `MainActivityLaunchTest#homeLoadsHomeUrl`
- **Evidence required**: instrumentation test result XML

### AC-004 — Journey J1: launch, browse, navigate
- **Feature**: F-01/F-03
- **Given**: the app is running with connectivity
- **When**: the user opens the home page, follows an in-page link, presses Back twice and presses Home
- **Then**: each transition is reflected in the omnibox/progress UI and the app remains in the foreground
- **Test method**: `JourneyInstrumentedTest#j1BrowseThenBackThenHome`
- **Evidence required**: instrumentation result XML + emulator screenshot artifact

### AC-005 — Journey J2: omnibox search
- **Feature**: F-02 Omnibox
- **Given**: the omnibox is empty
- **When**: the user types `android 15 sdk` and submits
- **Then**: the resolved URL is the configured provider's search URL with the term URL-encoded and the navigation starts
- **Test method**: `OmniboxResolverTest#searchTermsResolveToProviderUrl`, `MainActivityLaunchTest#submittingQueryLoadsSearchUrl`
- **Evidence required**: JVM test XML + instrumentation test XML

### AC-006 — Journey J3: bookmarks
- **Feature**: F-05 Bookmarks
- **Given**: a page is displayed
- **When**: the user taps the star action and opens the Bookmarks screen
- **Then**: the entry is persisted, appears in the list with its title, and opens when tapped
- **Test method**: `JourneyInstrumentedTest#j3BookmarkCurrentPageAndOpenIt`, `BookmarkRepositoryTest#upsertThenRead`
- **Evidence required**: instrumentation + JVM test XML

### AC-007 — Journey J4: history
- **Feature**: F-06 History
- **Given**: the app has visited at least one `https://` page
- **When**: the user opens the History screen
- **Then**: the visit is listed newest-first with title and URL; tapping it loads the URL again
- **Test method**: `JourneyInstrumentedTest#j4HistoryRecordsVisitAndReopensIt`
- **Evidence required**: instrumentation test XML

### AC-008 — Journey J5: tabs
- **Feature**: F-04 Tabs
- **Given**: one tab is open
- **When**: the user opens a new tab, switches back to the first, and closes the second
- **Then**: the tab counter reflects 1 → 2 → 1, the active tab's URL is displayed, and closing the last tab opens a fresh home tab instead of leaving the browser empty
- **Test method**: `TabManagerTest#switchAndCloseKeepsAtLeastOneTab`, `JourneyInstrumentedTest#j5TabsOpenSwitchClose`
- **Evidence required**: JVM + instrumentation test XML

### AC-009 — Journey J6: offline and retry
- **Feature**: F-07 Offline handling
- **Given**: the device has no validated network
- **When**: the user submits a URL
- **Then**: no navigation is attempted; the offline error state is shown with a Retry control; when
  connectivity returns and Retry is tapped the page loads
- **Test method**: `ViewStateMachineTest#offlineShowsRetry`, `JourneyInstrumentedTest#j6OfflineShowsRetryState`
- **Evidence required**: JVM + instrumentation test XML

### AC-010 — Tabs survive configuration change
- **Feature**: F-04/F-10
- **Given**: two tabs are open
- **When**: the activity is recreated (`ActivityScenario.recreate()`)
- **Then**: both tabs are restored, the active tab index is preserved and the tab count badge shows 2
- **Test method**: `StateRestoreTest#tabsSurviveRecreation`
- **Evidence required**: instrumentation test XML

### AC-011 — Tab list shows title and URL
- **Feature**: F-04
- **Given**: tabs with known titles/URLs
- **When**: the tab switcher is opened
- **Then**: each row shows the title (falling back to host name) and the URL, and the active row is marked
- **Test method**: `TabsAdapterTest#bindsTitleAndUrl`
- **Evidence required**: JVM (Robolectric-free unit) — see note in §3: adapters are covered in
  instrumentation when a `Context` is required; this AC is executed as `DisplayTextTest#titleFallsBackToHost`

### AC-012 — Empty tab list is impossible
- **Feature**: F-04
- **Given**: the last remaining tab
- **When**: it is closed
- **Then**: a new home tab is created immediately
- **Test method**: `TabManagerTest#closingLastTabOpensHomeTab`
- **Evidence required**: JVM test XML

### AC-013 — Invalid omnibox input is rejected safely
- **Feature**: F-02
- **Given**: the omnibox contains a blocked scheme (`javascript:alert(1)`, `file:///etc/passwd`, `data:text/html,x`)
- **When**: the user submits
- **Then**: no navigation occurs, the blocked-scheme error state is shown and the app does not crash
- **Test method**: `OmniboxResolverTest#blockedSchemesNeverResolveToNavigation`,
  `MainActivityLaunchTest#blockedSchemeIntentShowsErrorState`
- **Evidence required**: JVM + instrumentation test XML

### AC-014 — Malformed input does not crash
- **Feature**: F-02
- **Given**: inputs such as `https://`, `://x`, `%%%`, a 100 KB string, and emoji
- **When**: each is resolved
- **Then**: each resolves to `BLOCKED`/`SEARCH` deterministically and no exception escapes
- **Test method**: `OmniboxResolverTest#malformedInputsAreHandledWithoutException`
- **Evidence required**: JVM test XML

### AC-015 — Host-only input is upgraded to HTTPS
- **Feature**: F-02
- **Given**: the input `example.com` or `example.com:8443/path`
- **When**: it is resolved
- **Then**: the result is `https://example.com` / `https://example.com:8443/path` (never `http://`)
- **Test method**: `OmniboxResolverTest#hostLikeInputBecomesHttpsUrl`
- **Evidence required**: JVM test XML

### AC-016 — Bookmarks empty state
- **Feature**: F-05
- **Given**: no bookmarks exist
- **When**: the Bookmarks screen opens
- **Then**: the list is hidden and the "No bookmarks yet" empty state is visible with an accessibility label
- **Test method**: `BookmarksActivityTest#emptyStateIsShown`
- **Evidence required**: instrumentation test XML

### AC-017 — History empty state
- **Feature**: F-06
- **Given**: history was cleared
- **When**: the History screen opens
- **Then**: the "No history yet" empty state is visible
- **Test method**: `HistoryActivityTest#emptyStateIsShown`
- **Evidence required**: instrumentation test XML

### AC-018 — Empty content is never blank
- **Feature**: F-05/F-06
- **Given**: an item with a null/empty title
- **When**: it is displayed
- **Then**: the host name is shown instead of an empty row
- **Test method**: `DisplayTextTest#titleFallsBackToHost`
- **Evidence required**: JVM test XML

### AC-019 — Loading state
- **Feature**: F-07
- **Given**: a navigation has started
- **When**: the WebView reports progress < 100 and then 100
- **Then**: the progress bar is visible and updated, then hidden at completion; the content view replaces the loading state
- **Test method**: `ViewStateMachineTest#progressShowsAndHidesLoading`,
  `MainActivityLaunchTest#progressIndicatorExists`
- **Evidence required**: JVM + instrumentation test XML

### AC-020 — Error state for a failed main-frame load
- **Feature**: F-07
- **Given**: a main-frame load fails (host unresolved / no connectivity)
- **When**: `onReceivedError` is delivered
- **Then**: the error state with a Retry control is shown over the WebView instead of the raw WebView error page
- **Test method**: `ViewStateMachineTest#errorShowsRetryAndMessage`, emulator journey AC-009
- **Evidence required**: JVM test XML + emulator logcat excerpt

### AC-021 — SSL failure is fail-closed
- **Feature**: F-01
- **Given**: the WebView receives an SSL error
- **When**: `onReceivedSslError` runs
- **Then**: `handler.cancel()` is called unconditionally and the "Connection not secure" error state is shown
- **Test method**: `SslDecisionTest#cancelIsAlwaysCalled` (JVM unit test, `app/src/test/java`) and the
  instrumented `SslPolicyTest#tlsFailureIsRefusedAndVisible` (emulator, real certificate failure)
- **Evidence required**: JVM test XML + `connectedDebugAndroidTest` XML

### AC-022 — Cleartext HTTP is blocked
- **Feature**: F-01
- **Given**: any `http://` URL (omnibox, intent, or in-page link)
- **When**: navigation is attempted
- **Then**: `UrlPolicy.validate` returns `BLOCKED_SCHEME`, no `loadUrl` is executed and the blocked-scheme error state is shown
- **Test method**: `UrlPolicyTest#cleartextIsBlocked`, `MainActivityLaunchTest#httpIntentIsBlocked`
- **Evidence required**: JVM + instrumentation test XML

### AC-023 — History persists across restart
- **Feature**: F-06/F-10
- **Given**: a visit was recorded
- **When**: the process/DB helper is closed and re-opened (fresh helper = restart proxy)
- **Then**: the entry is still returned by `recentHistory`
- **Test method**: `PersistenceTest#historySurvivesHelperReopen`
- **Evidence required**: instrumentation test XML

### AC-024 — Bookmarks persist across restart
- **Feature**: F-05/F-10
- **Given**: a bookmark was added
- **When**: the helper is closed and re-opened
- **Then**: the bookmark is still present exactly once
- **Test method**: `PersistenceTest#bookmarksSurviveHelperReopen`
- **Evidence required**: instrumentation test XML

### AC-025 — Settings persist
- **Feature**: F-12
- **Given**: the user selects the Google provider and enables desktop mode
- **When**: preferences are re-read from a new store instance
- **Then**: both values are retained
- **Test method**: `PersistenceTest#settingsSurviveReopen`
- **Evidence required**: instrumentation test XML

### AC-026 — Rotation keeps the page
- **Feature**: F-10
- **Given**: a page is loaded
- **When**: the device rotates
- **Then**: the Activity is not recreated for the configuration change (`configChanges` contract) and the URL is unchanged; page state is retained
- **Test method**: `AndroidManifestContractTest#browserActivityHandlesOrientation`
- **Evidence required**: JVM/static test XML + manifest excerpt in evidence bundle

### AC-027 — Recreation restores browser state
- **Feature**: F-10
- **Given**: a tab list and a current URL
- **When**: `onSaveInstanceState` → `recreate()` → `onRestoreInstanceState`
- **Then**: the URL and tab list are restored without a crash
- **Test method**: `StateRestoreTest#currentUrlSurvivesRecreation`
- **Evidence required**: instrumentation test XML

### AC-028 — Permission requests are denied by default
- **Feature**: F-09
- **Given**: a page requests CAMERA, MICROPHONE or GEOLOCATION
- **When**: `onPermissionRequest` / geolocation prompt is delivered
- **Then**: the request is denied (fail-closed) and no runtime permission is requested from the user
- **Test method**: `PermissionPolicyTest#allWebPermissionRequestsAreDenied`
- **Evidence required**: JVM test XML

### AC-029 — Denial is visible, not silent
- **Feature**: F-09
- **Given**: a denied WebView permission request
- **When**: the denial happens
- **Then**: a user-visible message ("Permission denied") is shown and a `PermissionRequest.DENY` result is recorded
- **Test method**: `PermissionPolicyTest#denialIsReported`, emulator logcat assertion `W/SavageBrowser` denial line
- **Evidence required**: JVM test XML + emulator logcat

### AC-030 — Back navigation inside a page
- **Feature**: F-03
- **Given**: the WebView can go back
- **When**: the system Back gesture is used
- **Then**: the WebView navigates back instead of the Activity finishing
- **Test method**: `BackNavigationTest#systemBackGoesBackInWebView`
- **Evidence required**: instrumentation test XML

### AC-031 — Back closes the app only at the root page
- **Feature**: F-03
- **Given**: a single tab at its first page
- **When**: the system Back gesture is used
- **Then**: the Activity finishes (app closes) and no navigation occurs
- **Test method**: `BackNavigationTest#systemBackFinishesAtRoot`
- **Evidence required**: instrumentation test XML

### AC-032 — Accessibility labels on interactive controls
- **Feature**: F-11
- **Given**: every `ImageButton`/`TextView`-button in the browser, bookmarks and history layouts
- **When**: the view hierarchy is inspected
- **Then**: each has a non-empty `contentDescription` from a string resource, and each touch target is ≥ 48dp
- **Test method**: `AccessibilityTest#allInteractiveControlsAreLabelled`
- **Evidence required**: instrumentation test XML

### AC-033 — No hard-coded UI strings
- **Feature**: F-11
- **Given**: all layout XML files
- **When**: they are parsed
- **Then**: no literal `android:text`/`android:hint` value is present (all use `@string/`)
- **Test method**: `shell/tests/test_android_assets.py` (repo-level) / CI step `gradlew :app:lintDebug`
  (lint `HardcodedText` is error-free)
- **Evidence required**: lint report + test XML

### AC-034 — Duplicate bookmark actions are idempotent
- **Feature**: F-05
- **Given**: a bookmark already exists for the current page
- **When**: the star action is tapped again
- **Then**: exactly one row exists for that URL and the user is told it already exists
- **Test method**: `BookmarkRepositoryTest#duplicateUpsertKeepsSingleRow`, `JourneyInstrumentedTest#j3StarTwiceKeepsOneRow`
- **Evidence required**: JVM + instrumentation test XML

### AC-035 — Duplicate history entries do not duplicate rows per visit URL
- **Feature**: F-06
- **Given**: the same URL is visited twice
- **When**: history is queried
- **Then**: the newest visit timestamp is recorded and the row count for that URL is bounded by the
  repository's upsert rule (one row per URL, `visit_count` incremented)
- **Test method**: `HistoryRepositoryTest#revisitUpdatesExistingRow`
- **Evidence required**: JVM (pure SQL contract) + instrumentation test XML

### AC-036 — Duplicate Retry / double tap
- **Feature**: F-07
- **Given**: the error state with Retry
- **When**: Retry is tapped twice quickly while offline
- **Then**: exactly one offline message is produced and no navigation storm occurs
- **Test method**: `ViewStateMachineTest#doubleRetryIsCoalesced`
- **Evidence required**: JVM test XML

### AC-037 — Process restart restores the session
- **Feature**: F-10
- **Given**: the app was killed by the system (`adb shell am kill`)
- **When**: it is relaunched from the launcher
- **Then**: it starts cleanly at the restored/home tab without crash; persisted history and bookmarks are intact
- **Test method**: `tools/android_apk_smoke.sh` steps 9-10 (`adb shell am kill com.savage.soulbrowser` + relaunch twice)
- **Evidence required**: emulator logcat + `am start -W` output in the evidence bundle

### AC-038 — Corrupted local database is recovered
- **Feature**: F-10
- **Given**: the on-disk database file is overwritten with junk bytes
- **When**: the repository opens it
- **Then**: the corrupt file is deleted, a fresh schema is created, the empty state is served and no crash occurs
- **Test method**: `CorruptionRecoveryTest#corruptDatabaseIsRecreated`
- **Evidence required**: instrumentation test XML

### AC-039 — Missing local data is not an error
- **Feature**: F-10
- **Given**: no database file exists
- **When**: history/bookmarks are queried
- **Then**: empty lists are returned (no exception) and the empty states render
- **Test method**: `RepositoryEmptyStateTest#queriesOnFreshDatabaseReturnEmptyLists`
- **Evidence required**: instrumentation test XML

### AC-040 — Privileged bridge is origin-gated
- **Feature**: F-08
- **Given**: the bridge is injected into an untrusted page (or has no page)
- **When**: `getAppVersion()` / `postFeedback()` is invoked
- **Then**: it returns `ACCESS_DENIED` / performs no action, and the trusted origin succeeds
- **Test method**: `TrustedOriginsTest#untrustedOriginsAreRejected` (JVM), `BridgePolicyTest#untrustedOriginsAreRejected` and `BridgePolicyTest#feedbackValidation` (instrumented, real bridge)
- **Evidence required**: JVM test XML

### AC-041 — Misleading host names do not pass the bridge check
- **Feature**: F-08
- **Given**: `https://evil-savage-tech-corner.io`, `https://savage-tech-corner.io.evil.com`,
  `https://savage-tech-corner.io:8443`, `http://savage-tech-corner.io`, `https://SAVAGE-TECH-CORNER.IO`
- **When**: each is evaluated
- **Then**: only the exact HTTPS host on the default port is privileged
- **Test method**: `TrustedOriginsTest#spoofedHostsAreRejected` (JVM), `BridgePolicyTest#spoofedAndUnknownOriginsAreRejected` (instrumented)
- **Evidence required**: JVM test XML

### AC-042 — Bridge input validation
- **Feature**: F-08
- **Given**: feedback payloads including `<script>`, SQL fragments, control characters, a 501-char
  string, and legitimate Unicode text
- **When**: validated
- **Then**: only Unicode-letter/digit/punctuation payloads ≤ 500 chars pass
- **Test method**: `InputValidatorTest#rejectsInjectionAndControlCharacters`, `#acceptsUnicode` (JVM), `BridgePolicyTest#feedbackValidation` (instrumented, including the 500-character limit and the sanitized echo)
- **Evidence required**: JVM test XML

### AC-043 — No unnecessary permissions
- **Feature**: F-01/F-13
- **Given**: the built APK manifest
- **When**: permissions are listed (`aapt2 dump badging` / `androguard`)
- **Then**: the set is exactly `INTERNET` + `ACCESS_NETWORK_STATE`; no dangerous permission, no
  cleartext traffic, `usesCleartextTraffic=false`, network security config present
- **Test method**: `AndroidManifestContractTest#permissionsAreMinimal`, CI step `aapt2 dump badging`
- **Evidence required**: badging output in the evidence bundle

### AC-044 — Release APK installs and launches
- **Feature**: F-13
- **Given**: the built debug-signed APK (`artifacts/debug/SavageBrowser-debug.apk`)
- **When**: it is installed on a clean emulator (`adb install -r`) and launched
- **Then**: installation succeeds, `MainActivity` is the launcher activity, the process starts and
  `am start -W` reports `Status: ok` without crash
- **Test method**: `tools/android_apk_smoke.sh` (install, launch, kill, relaunch, uninstall and reinstall on the CI emulator) and `tools/reproducible_build_verifier.py --apk <apk> --expect-signed`
- **Evidence required**: emulator install/launch log, screenshot, APK SHA-256

### AC-045 — Package identity and version metadata
- **Feature**: F-13
- **Given**: the built APK
- **When**: its manifest is read
- **Then**: `package=com.savage.soulbrowser`, `versionCode=5`, `versionName=3.0.0`, `minSdk=26`, `targetSdk=35`
- **Test method**: `tools/reproducible_build_verifier.py --apk <apk> --json apk-evidence.json` (reads the expected identity from `app/build.gradle.kts`)
- **Evidence required**: verifier stdout + `android-artifact-verification.txt`

### AC-046 — The APK is signed and provenance matches its hash
- **Feature**: F-13
- **Given**: the built APK and `artifacts/manifests/RELEASE_MANIFEST.json`
- **When**: `apksigner verify` runs and the SHA-256 is recomputed
- **Then**: v1+v2 signing verify, the signer is the Android debug key (intentional for this build),
  and the recorded SHA-256 equals the recomputed SHA-256 of the file
- **Test method**: `tools/reproducible_build_verifier.py --apk <apk>` (SHA-256 + certificate identity in `apk-evidence.json`) and the CI step `apksigner verify --print-certs`
- **Evidence required**: apksigner output + `sha256sum` output in the evidence bundle

### AC-047 — Reproducible from a clean checkout
- **Feature**: F-13
- **Given**: a fresh clone of this branch at the built commit
- **When**: the documented commands run (`gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`)
- **Then**: they complete successfully and produce a functionally equivalent APK
- **Test method**: `.github/workflows/android-build.yml` (clean checkout on GitHub-hosted runners), plus
  the committed Gradle wrapper pin (`gradle-8.11.1`, AGP `8.7.3`)
- **Evidence required**: CI run URL + job log summary committed to `artifacts/reports/`

---

## 2. Traceability summary

| Requirement category (Phase 2 checklist) | Criteria |
|---|---|
| clean first launch | AC-001 |
| normal navigation | AC-002, AC-003 |
| core user journeys | AC-004 – AC-009 |
| invalid input | AC-013, AC-014, AC-015 |
| empty state | AC-016 – AC-018 |
| loading state | AC-019 |
| error state | AC-020 |
| offline behaviour | AC-009, AC-021 |
| persistence after restart | AC-023 – AC-025 |
| configuration changes / rotation | AC-026, AC-027, AC-010 |
| permission denial | AC-028, AC-029 |
| back navigation | AC-030, AC-031 |
| accessibility labels | AC-032, AC-033 |
| duplicate actions | AC-034 – AC-036 |
| process restart | AC-037 |
| corrupted / missing local data | AC-038, AC-039 |
| release APK installation and launch | AC-044 – AC-047 |
| security invariants | AC-021, AC-022, AC-040 – AC-043 |

## 3. Test-method notes (no test is weakened to pass)

* **AC-021 (`SslDecisionTest` + instrumented `SslPolicyTest`)**: `android.webkit.SslErrorHandler`
  cannot be instantiated or subclassed by an app (`SslErrorHandler()` is `@hide`/`@SystemApi` and the
  SDK stub makes it package-private), and on the platform a bare instance's `cancel()` is an empty
  method - only the WebView's own provider subclass reacts. A fake handler would therefore prove
  nothing. The criterion is covered at two levels instead:
  1. `browser/SslDecision.java` holds the whole decision as pure Java; `SslDecisionTest` asserts that
     every SSL error code and every host yields `CANCEL` and that no other outcome exists
     (`SslDecision.Action` has a single constant).
  2. The instrumented `SslPolicyTest#tlsFailureIsRefusedAndVisible` drives a real certificate failure
     through the real WebView and the production client on the emulator and asserts the user-visible
     consequence of cancelling: the page never renders, the session has no loaded content, and the
     "Connection not secure" state with Retry is shown. Had the client called `proceed()`, the page
     would have rendered and the test would fail.
  3. A repository check (`shell/tests/test_android_assets.py`) fails the build if `proceed(` ever
     appears in the application sources, so the fail-closed invariant cannot regress silently.
* **AC-011 / AC-032**: adapter/UI coverage that needs a real `Context` runs as instrumentation tests;
  the pure display logic (`DisplayText.titleFor`) is additionally unit-tested on the JVM so the
  assertion exists in both layers.
* Instrumented tests run on a real Android emulator in CI (`connectedDebugAndroidTest`). They are not
  skipped, not `@Ignore`d, and their result XML is committed as evidence.
