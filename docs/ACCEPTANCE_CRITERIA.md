# Savage Browser — Acceptance Criteria (authoritative for the Android deliverable)

Every acceptance criterion below is executable: `Test method` names a real test (JVM unit test,
instrumented test, or a shell/Gradle command) that is present in this repository.

---

## 0. Brief resolution and recorded ambiguities

The implementation brief supplied to this session contained **unfilled placeholders**
(`[APP NAME]`, `[WHAT THE APP MUST DO]`, `[MIN SDK]`, `[COM.APP.PACKAGE]`, …). It also asked for
`python3 SAVAGE_FACTORY.py --root . gate`, but no `SAVAGE_FACTORY.py` existed at the repository
root (the factory shipped only as a ZIP payload).

Per the operating rule _"if a requirement is unclear, record the ambiguity and choose the safest
reasonable behavior; do not silently invent a major product decision"_, the brief was resolved
from the repository's own authoritative documents instead of being invented:

| Brief field             | Resolved value                                                                 | Source of truth                                                  |
| ----------------------- | ------------------------------------------------------------------------------ | ---------------------------------------------------------------- |
| App name                | **Savage Browser**                                                             | `README.md` §1, `docs/ARCHITECTURE.md` §1                        |
| Goal                    | Security-hardened Android WebView browser for the Savage Tech Corner ecosystem | `README.md` §1, `docs/SECURITY.md` §1                            |
| Target users            | Privacy/security-conscious Android users of the Savage ecosystem               | `docs/SECURITY.md`, `docs/ARCHITECTURE.md` §5                    |
| Package / applicationId | `com.savage.soulbrowser`                                                       | `README.md`, `factory-3/tools/android_vm_verifier.py`            |
| Minimum SDK             | 26 (Android 8.0)                                                               | `README.md` ("API 26 to 35"), existing `app/build.gradle.kts`    |
| Target SDK              | 35 (Android 15)                                                                | `README.md` ("API 26 to 35"), `factory-3/tools/android_tools.py` |
| Permissions             | `INTERNET`, `ACCESS_NETWORK_STATE` only                                        | existing manifest, `docs/ARCHITECTURE.md` §5                     |
| Offline behaviour       | Offline-resilient: local data served, clean offline UI, retry                  | `docs/ARCHITECTURE.md` §5.3                                      |
| Persisted data          | Bookmarks + history in local SQLite, queued for later sync                     | `docs/ARCHITECTURE.md` §5.3                                      |
| Security invariants     | HTTPS-only, fail-closed TLS, origin-gated JS bridge                            | `docs/SECURITY.md` §1                                            |

### Ambiguity log (decisions taken, with rationale)

| #   | Ambiguity                                                                                        | Decision                                                                                                                                                                                                                                                                                                                                                            | Rationale                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| --- | ------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| A1  | Brief placeholders                                                                               | Resolved from repo docs (table above)                                                                                                                                                                                                                                                                                                                               | Rule 11; no major product decision invented                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| A2  | `SAVAGE_FACTORY.py` missing at root                                                              | Not adopted. Both engine archives stay bundled and unextracted (`SAVAGE_FACTORY_v4.zip`, `SAVAGE_FACTORY_v4_DEBUG_FINAL.zip`), and every gate that runs in this repository is the repository's own (`tools/reproducible_build_verifier.py`, `shell/tests`, `.claude/scripts/gates.sh`)                                                                              | Extracting a self-modifying agent engine with prebuilt outputs is not required by the Android deliverable, and the archives' own contents are hash-pinned by `tools/anti_deception_auditor.py` so an altered archive is a violation rather than an exemption                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| A3  | Materialising the factory (`init`) into the product repo                                         | **Not** materialised in-tree                                                                                                                                                                                                                                                                                                                                        | `NO_PADDING.md` + `docs/factory/ENFORCEMENT.md` §4.4 require one canonical factory tree; `FACTORY_REVIEW.md` P2 #19 explicitly forbids adding a parallel factory. `init`+`doctor` were executed in an isolated scratch copy instead (evidence recorded)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| A4  | "Required screens" (3) not specified                                                             | Browser, Bookmarks, History                                                                                                                                                                                                                                                                                                                                         | Screens that the existing AppBar/dock controls and `docs/ARCHITECTURE.md` imply; each is a real Activity                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| A5  | Tabs button previously showed a `Toast` (fake success)                                           | Implemented real tabs; multi-WebView sessions with a switcher dialog                                                                                                                                                                                                                                                                                                | Rule 5 (no fake success responses); a toast is not a feature                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| A6  | AppBar "AI assistant" button had no implementation                                               | Removed; replaced by a working Bookmark (star) action                                                                                                                                                                                                                                                                                                               | Rule 5; an unimplemented control is a dead control (`factory-2/criticism/dead_buttons_check.py` policy)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| A7  | `activities.BrowserActivity` existed only as an unreferenced exported alias                      | Removed                                                                                                                                                                                                                                                                                                                                                             | An exported component with a `MAIN` filter and no launcher category is dead surface area; `android_vm_verifier.py` accepts `MainActivity`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| A8  | Default search provider                                                                          | DuckDuckGo (Google/Bing selectable)                                                                                                                                                                                                                                                                                                                                 | Security/privacy posture of the product; user-selectable, persisted                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| A9  | Non-443 HTTPS ports                                                                              | Allowed                                                                                                                                                                                                                                                                                                                                                             | Blocking them breaks ordinary sites; the security boundary is the scheme + TLS validation, not the port                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| A10 | Version bump                                                                                     | versionCode 5, versionName 3.0.0                                                                                                                                                                                                                                                                                                                                    | The rework changes package contents and behaviour; previous shipped value was 4 / 2.0.4                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| A11 | Release signing credentials                                                                      | Not invented. Release variant is configured for a real keystore supplied via `keystore.properties`/env; the verified artifact is the debug-signed debug APK                                                                                                                                                                                                         | Rule 9 (never invent secrets)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| A12 | `tools/vm_crash_and_install_debugger.py` claimed emulator/install results it cannot produce      | Relabelled: it is a static APK structure analyser; real install/launch evidence now comes from the CI emulator job                                                                                                                                                                                                                                                  | Rule 3 (no invented emulator results)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| A13 | `factory-3` APK "build" copied a pre-existing APK and byte-patched a signing block               | Replaced with a real Gradle build, or an explicit failure when no Android toolchain exists                                                                                                                                                                                                                                                                          | Rule 3/13; a copied APK is not a build                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| A14 | Does a _blocked_ navigation offer Retry?                                                         | No: Retry is offered for load errors, offline and TLS refusals, never for a blocked address                                                                                                                                                                                                                                                                         | Blocking comes from a static rule (unsupported scheme, cleartext, host not allowed), so repeating the request cannot succeed; the earlier JVM expectation was wrong and `ViewStateMachineTest` pins the corrected rule                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| A15 | How do journeys load a page without depending on emulator egress?                                | Journeys that prove what the browser does with an _address_ submit it through the omnibox (`TestSupport.navigateForReal`); `injectPage` stays for content/title/history-stack assertions                                                                                                                                                                            | The criteria describe the browser's behaviour, not a third party's response; Chromium reports a `loadDataWithBaseURL` load as a `data:` URL, so those tests must not assert `getUrl()`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| A16 | Where is "a certificate error is refused" proven, when the handler cannot be observed on device? | `SslDecisionTest` (JVM: CANCEL for every error code) + `shell/tests/test_ssl_fail_closed.py` (no application source contains the accept path) + the instrumented `SslPolicyTest` for the visible refusal                                                                                                                                                            | The SDK declares no public constructor for `SslErrorHandler`, so no instrumented test can subclass it; a network-based test would only show that a page did not render and would depend on egress                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| A17 | An address is chosen in the bookmarks or history screen while a browser is already open          | The browser that is already in the task is brought to the front and navigated: the list sends `FLAG_ACTIVITY_CLEAR_TOP                                                                                                                                                                                                                                              | FLAG_ACTIVITY_SINGLE_TOP`, so `MainActivity.onNewIntent` performs the navigation                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | Stacking a second browser instance on top of the first would leave one task with two browsers, and "the browser in front is back on that address" would stop being observable at all Stacking a second browser instance would leave one task with two browsers and make "the browser in front is back on that address" unobservable; for the same reason the instrumented journeys reach that screen with `Instrumentation.startActivitySync` rather than `ActivityScenario.launch`, which clears the task it launches into - including the browser the row is supposed to come back to. |
| A18 | How is the in-flight Reload/Stop state observed when the load finishes faster than any poll?     | `NavigationStateTest#reloadStopsLoadingAndHomeResets` delivers the WebView's own `onPageStarted` / `onPageFinished` callbacks (the technique `MainActivityLaunchTest` already uses for the progress bar) and keeps both clicks on the action real                                                                                                                   | On the CI emulator the requested name does not resolve, so the load is over in milliseconds and an assertion that waited for the real in-flight moment only passed while an _earlier_ load happened to still be running - it broke as soon as the navigation before it really settled; the reload branch's effect on the WebView cannot be polled either, so what is asserted there is the state the user is left in (committed on the same address, idle, usable)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| A19 | May a WebView callback for a page the browser has already left change the browser?               | No: `SavageWebViewClient#stillCurrent` drops `onPageFinished` / `onReceivedError` for an address the WebView is no longer on, while `onPageStarted` and `onReceivedSslError` stay unguarded                                                                                                                                                                         | Chromium reports the end of the home page's load _after_ the browser was told to open another address, and acting on it set the active tab's address, title, history entry and failure state back to the old page - across a recreation that is visible as the page the user was on being replaced by the page before it. At the moment a load finishes the WebView's own address is safe to compare with (a success and Chromium's own error page both report the loaded address, and a superseded load reports the newer one), which is what tells a stale callback from a redirect - the redirect reports the address it ended up on. `onPageStarted` is not guarded because the framework documents that the current page may not have changed yet although a load has begun, so a dropped callback would leave a real load invisible to the toolbar; `onReceivedSslError` is not guarded because a refused connection aborts the load (the WebView's address legitimately stays the previous page) and hiding a refusal is the one thing this browser must never do |
| A20 | Is an APK that ships inside the repository evidence for a build?                                 | No. The four prebuilt APKs (all the 1.0.0-savage build) stay bundled as A2 records, but they are declared as prebuilt in `shell/tests/test_android_assets.py`, no workflow, script or gate may name them, and the pipeline the belt runs may only certify an APK whose `<name>.apk.sha256` provenance record - written by the build stage itself - matches the file | A file this checkout did not build says nothing about this checkout. The earlier pipeline certified whatever sat at the final-product path, and the four stale APKs are exactly the kind of file that could be mistaken for a build output; the build stage already fails closed, and the pipeline now refuses to judge an APK whose provenance it did not record                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |

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
- **When**: the reload action is tapped while loading, again on the settled page, then the home action is tapped
- **Then**: the first tap stops the load and the action advertises Reload again, the second tap asks the browser to load that page once more and leaves it committed on that address, and the home tap loads the home URL
- **Test method**: `NavigationStateTest#reloadStopsLoadingAndHomeResets` (real clicks on the action in both states; the in-flight moment is reached by delivering the WebView's own `onPageStarted`, see A18), `MainActivityLaunchTest#homeLoadsHomeUrl`
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
  `MainActivityLaunchTest#progressIndicatorExists`. The instrumented test performs a real navigation
  and then delivers the same callbacks the WebView client delivers (`onPageStarted` / `onProgress` /
  `onPageFinished`) and asserts what the user sees: Chromium's own progress callback timing is not
  schedulable, and on a runner whose DNS answers immediately the whole load can finish inside one
  polling interval.
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
- **Test method**: `SslDecisionTest` (JVM: every framework error code resolves to CANCEL),
  `shell/tests/test_ssl_fail_closed.py` (no application source contains the accept path at all) and
  the instrumented `SslPolicyTest#tlsFailureIsRefusedAndVisible` (emulator: the real client receives
  a real `SslError` and the refusal is shown on screen - see A16 for why the handler call itself is
  pinned by source and JVM checks instead)
- **Evidence required**: JVM test XML + shell test output + `connectedDebugAndroidTest` XML

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
- **Given**: a tab list and a current URL that has been committed (`waitForSettledNavigation` - `WebView.saveState()` stores the current entry of the page history, so a load that is still in flight is not part of the state that is saved)
- **When**: `onSaveInstanceState` → `recreate()` → `onRestoreInstanceState`
- **Then**: the URL and tab list are restored without a crash
- **Test method**: `StateRestoreTest#currentUrlSurvivesRecreation`, plus `SavageWebViewClient#stillCurrent` (a page the browser has left may not rewrite the tab, see A19) pinned by `shell/tests/test_android_assets.py`
- **Evidence required**: instrumentation test XML

### AC-028 — Permission requests are denied by default

- **Feature**: F-09
- **Given**: a page requests CAMERA, MICROPHONE or GEOLOCATION
- **When**: `onPermissionRequest` / geolocation prompt is delivered
- **Then**: the request is denied (fail-closed) and no runtime permission is requested from the user
- **Test method**: `PermissionPolicyTest#allWebPermissionRequestsAreDenied`, `WebPermissionTest#theInstalledPackageRequestsNoRuntimePermission`, `WebPermissionTest#geolocationPromptIsDeniedAndReported`
- **Evidence required**: JVM test XML + instrumentation test XML

### AC-029 — Denial is visible, not silent

- **Feature**: F-09
- **Given**: a denied WebView permission request
- **When**: the denial happens
- **Then**: a user-visible message ("Permission denied") is shown and a `PermissionRequest.DENY` result is recorded
- **Test method**: `PermissionPolicyTest#denialIsReported` (policy), `WebPermissionTest#geolocationPromptIsDeniedAndReported` (the real chrome client installed on the real WebView answers the platform callback with deny), `WebPermissionTest#denialIsLoggedForTheEmulatorLog` (logcat `W/SavageBrowser` line, read back through `UiAutomation`)
- **Evidence required**: JVM test XML + instrumentation test XML + emulator logcat

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
- **Note**: the repository reports `ALREADY_PRESENT` for a page it already holds and leaves the stored
  row exactly as the user created it (title, URL and creation time are not rewritten); the star is a
  toggle, so the second tap on the same page removes the bookmark, and one row can never exist twice.
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
- **Given**: the app's process was ended by the platform (`adb shell am force-stop`)
- **When**: it is relaunched from the launcher
- **Then**: it starts cleanly at the restored/home tab without crash; persisted history and bookmarks are intact
- **Test method**: `tools/android_apk_smoke.sh` steps 9-11 (`am force-stop com.savage.soulbrowser` + relaunch twice, then a live-process check; `am kill` only reclaims _background_ processes, so the foreground browser survived it and the step failed on a healthy build)
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
- **Given**: the debug-signed APK this checkout builds - the build output `app/build/outputs/apk/debug/app-debug.apk` of `shell/android_project/`, published as the `savage-browser-apk` artifact together with `SHA256SUMS.txt` (a build output is not a repository file; the copy under `artifacts/debug/` is a stale prebuilt file and is never build evidence - see A2, A13)
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
- **Test method**: `tools/reproducible_build_verifier.py --apk <apk> --json apk-evidence.json` (reads the expected identity from `app/build.gradle.kts`), gate behaviour pinned by `shell/tests/test_apk_gate.py#ExpectedIdentityTest`
- **Evidence required**: verifier stdout + `android-artifact-verification.txt`

### AC-046 — The APK is signed and provenance matches its hash

- **Feature**: F-13
- **Given**: the APK this checkout builds and the evidence published next to it (`SHA256SUMS.txt`, `apk-evidence.json`, `apksigner-debug.txt`; `artifacts/manifests/RELEASE_MANIFEST.json` is the _factory_ certification record, not an APK hash manifest)
- **When**: `apksigner verify` runs and the SHA-256 is recomputed
- **Then**: v1+v2+v3 signing verify, the signer is the Android debug key (intentional for this build), and the SHA-256 recorded by the build job equals the SHA-256 of the bytes the emulator job installs (`sha256sum -c SHA256SUMS.txt` before installing)
- **Also**: inside the repository's own pipeline the same rule is enforced before certification - the build stage publishes `<apk>.sha256` next to what it built and the pipeline refuses to continue when that record is missing or does not match the file (A20)
- **Test method**: `tools/reproducible_build_verifier.py --apk <apk>` (SHA-256 + certificate identity in `apk-evidence.json`), `shell/tests/test_apk_gate.py#FailClosedExitCodeTest` (fails closed, exits non-zero, never claims a pre-built APK as provenance) and the CI step `apksigner verify --print-certs`
- **Evidence required**: apksigner output + `sha256sum` output in the evidence bundle

### AC-047 — Reproducible from a clean checkout

- **Feature**: F-13
- **Given**: a fresh clone of this branch at the built commit
- **When**: the documented commands run (`gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`)
- **Then**: they complete successfully and produce a functionally equivalent APK (the same
  variant, identity, version, permission set, launcher activity and v1+v2+v3 signing scheme - a
  byte-identical file needs a pinned signing key, which this repository does not commit: see A11,
  and the two green runs recorded in `artifacts/reports/ci-evidence-ca8f4d3.md`, whose debug keys
  therefore differ)
- **Test method**: `.github/workflows/android-build.yml` (clean checkout on GitHub-hosted runners), plus
  the committed Gradle wrapper pin (`gradle-8.11.1`, AGP `8.7.3`)
- **Evidence required**: CI run URL + job log summary committed to `artifacts/reports/` (`artifacts/reports/ci-evidence-ca8f4d3.md` holds the two green runs of this branch, their job
  and step conclusions, the build job's APK annotation verbatim, and the runs before them with
  what they failed on)

### AC-048 — The sealed factory set runs before the pipeline's own factories, and is removable

- **Feature**: F-13 (the pipeline that produces the release)
- **Given**: the sealed component `sealed-factories/` (149 records - 84 imported factories, 23
  AI-focused factories and 42 MCP servers - with the 127 licences they publish, hash-sealed) and the pipeline that uses it (`scripts/conveyor_belt.js` station 0, then the four
  current factories, as documented in `docs/factory/MULTISTAGE_FACTORY.md`)
- **When**: the seal is verified and the set is run, then the pipeline runs again with the directory
  deleted
- **Then**: every payload hash matches `SEAL.json` and a mismatch stops the run; every factory is
  accounted for in `RUN-REPORT.json` as `OPERATIONAL-MODEL` (a model answered, and the answer is on
  disk), `OPERATIONAL-PLAN` (its own stages and workers were read and its plan written) or
  `NO-STRUCTURE` (its slice carries no definitions - exactly the blueprint-only factories whose
  content the licence forbids carrying); a provider that refuses is recorded as `MODEL-FAILED` with
  its own error text and never as a success; with the directory deleted, station 0 reports `ABSENT`,
  the banner says so, the remaining six stations pass, and nothing outside `sealed-factories/`,
  its three tooling files, its one test and its one document is required to change
- **Test method**: `shell/tests/test_sealed_factory_runtime.py` (every payload hash recomputed, the
  licence policy, no invented model output, the online path driven against a local server speaking
  the providers' own API, and removal proven by running the runtime in an empty tree), plus the
  station itself in `scripts/conveyor_belt.js`
- **Evidence required**: the `[SEAL] OK` verification line and the station 0 line from a belt run
  with the component present and from one with it absent

---

### AC-049 — MCP servers are imported into the sealed environment and are functional there

- **Feature**: F-14 (the environment inside the seal)
- **Given**: the sealed component with its MCP group (`sealed-factories/mcp-vendor/`, the catalogue
  `sealed-factories/mcp-servers.json`, 42 servers) and the runtime's MCP client (`mcplink.py`,
  `toolserver.py`)
- **When**: the catalogue is probed (`cli.py mcp-probe`) and the servers this machine can start are
  bound into the tool registry (`--with-mcp`)
- **Then**: every catalogued server is listed with its repository, its launch command and where that
  command came from; a server is recorded as handshake-verified only after a real
  `initialize` handshake followed by a tool listing drove it (7 servers, 52 tools, evidence kept in
  `tools/factory/imported_mcp_servers.json`); a `python3 -m` server counts as bindable only when its
  module actually imports, and an npm server only when its launcher resolves; a server that cannot be
  started is named with the reason (missing binary, no published launch command, exited before
  answering) and never silently dropped; the tools `toolserver.py` serves over MCP speak the protocol
  a client expects, and every capability of the seven verified servers is also native in `toolkit.py`,
  so the environment is complete without any package manager
- **Test method**: `shell/tests/test_sealed_environment.py` (`ToolServerTest` drives an
  `initialize` handshake, a tool listing, a real tool call, an unknown tool and a path-escape
  refusal over stdio;
  `test_the_registry_holds_a_full_tool_set`; the availability assertions), plus
  `shell/tests/test_imported_factories.py` for the registry records, plus the live `mcp-probe` run
- **Evidence required**: the `mcp-probe` summary line (42 catalogued / 18 installed / 24 not
  bindable) and the servers that answered a handshake on this machine (this round: `servers` 13
  tools, `context7` 2, `playwright-mcp` 25)

### AC-050 — One gateway serves every model and holds every key the environment has

- **Feature**: F-14
- **Given**: `sealed-factories/runtime/gateway.py`, a stdlib HTTP server over 13 providers (local:
  llama.cpp, LM Studio, Ollama, vLLM; keyed: OpenAI, Anthropic, Groq, Mistral, DeepSeek, Together,
  OpenRouter, xAI, Google)
- **When**: a client asks `GET /healthz`, `GET /v1/models`, `GET /v1/keys` or
  `POST /v1/chat/completions`
- **Then**: `/healthz` and `/v1/models` report only providers that actually answered; `/v1/keys`
  reports which keys are configured and never a key value; chat completions route to any reachable
  provider, `key:auto`/`model:auto` pick one, multiple keys for one provider are used in turn, the
  Anthropic wire format is built correctly where the provider needs it, an upstream error is
  returned as that provider's error rather than a fabricated answer, and with nothing reachable the
  gateway answers **503** naming every endpoint it looked at; a provider that accepts the connection
  and never replies is cut off after `SEALED_GATEWAY_TIMEOUT` (60 s) and reported as **504**
- **Test method**: `shell/tests/test_sealed_environment.py` (`GatewayTest`: routing to a local stub
  on the provider's own port, the honest 503, key rotation, redaction, the Anthropic payload, the
  timeout path), and `toolkit.model.ask` refusing with no gateway
- **Evidence required**: the stub run's `/healthz`, `/v1/models` and `/v1/chat/completions` output and
  the 503 body listing `llamacpp:8080, lmstudio:1234, ollama:11434, vllm:8000`

### AC-051 — The environment works with no model, no keys and no network

- **Feature**: F-14
- **Given**: the sealed component on a machine with no model server, no API key in the environment
  and no reachable network - and, for the run below, `SEALED_NO_NETWORK=1`, which switches the
  component's own outbound calls off so the offline path is the path actually taken
- **When**: the runtime is run (`sealed_factory_runtime.py run --max-model-calls 0` or
  `cli.py run --model-calls 0`)
- **Then**: the run executes every planned agent instance, each one making real tool calls that touch
  the workspace, every check passes, 0 model calls are made and none are claimed, `model.ask` refuses
  with a message naming what it looked for instead of inventing an answer, the network tools fail with
  a reason, and the report records `OFFLINE` for those agents - a machine without a model still gets a
  working factory, and nothing in the run pretends otherwise. With the switch on, the whole
  environment runs in CI as its own job: `tools/ci/airgap_run.sh` verifies the seal, runs the sealed
  set with networking switched off, and `tools/ci/airgap_judge.py` accepts the run only when the
  report's own counts show every check passed, no step failed, no model call was made or claimed, and
  a role's own artefact records the switch as the reason its GitHub probe was unavailable - so a run
  that quietly reached the network is rejected rather than accepted as "offline"
- **Test method**: `shell/tests/test_sealed_environment.py` (`test_a_run_with_no_model_says_so_and_never_invents_one`,
  `test_model_ask_refuses_without_a_gateway`, `test_offline_network_tools_fail_with_a_reason`), plus
  `shell/tests/test_sealed_factory_runtime.py`, plus `shell/tests/test_airgap_judge.py` (ten tests),
  which reject the runs that must not count - a probe that reached the network, a missing or failed
  probe, failed checks, a failed step, a claimed model call - and pin that the CI job installs no npm
  or pip dependencies
- **Evidence required**: the sealed-run line (`0 factories ran against a model, 96 ran in plan mode,
0 model calls failed`), the per-run reports under `sealed-factories/manifest/`, and the
  `Air-gapped sealed run (SEALED_NO_NETWORK=1)` job's own output - the judge's line naming the
  researcher artefact and the probe summary `available=False; status=0; reason=network calls are
disabled: SEALED_NO_NETWORK is set`, or an `AIR-GAP FAIL` line saying which of those did not hold

### AC-052 — The swarm plans a million agents, executes what it is told to, and says what it did not do

- **Feature**: F-14
- **Given**: `sealed-factories/runtime/swarm.py` and the agent catalogue the seal defines
- **When**: capacity is asked for, and a run is executed with a limit
- **Then**: `capacity_report()` reports the 1,000,000-instance ceiling and refuses to plan more; a run
  executes **exactly** the limit it was given (a hard cap, not a minimum), instantiates agents lazily
  so a million-instance plan costs no memory, names in the same report the roles that got no instance,
  writes one artefact per executed instance plus a `RUN.json`, gives every run its own directory (two
  runs in the same second do not share one), runs each role's checks once against the workspace the
  makers touched, and records each instance as `MODEL`, `OFFLINE` or `MODEL-FAILED` - never as a
  success it did not have
- **Test method**: `shell/tests/test_sealed_environment.py` (`SwarmTest`: the million-instance plan,
  the hard cap, the artefacts on disk, the report's own path), and `cli.py capacity`
- **Evidence required**: the `capacity` output (159 roles, capacity 1,000,000, 6,289 instances per
  role) and the sealed run's swarm line (159 instances / 162 tool steps / 159 checks passed)

### AC-053 — Multi-agent coverage, including the AI-focused factories

- **Feature**: F-14
- **Given**: the three imported groups inside the seal (72 imported factories, 42 MCP tool servers,
  23 AI-focused factory projects) and their slices
- **When**: the runtime builds its agent catalogue
- **Then**: every sealed factory that defines stages, roles, gates or prompts becomes agent roles, and
  every MCP server becomes an `mcp-<id>` role, so the swarm's roles are derived from the sealed slices
  rather than from a hand-written list (this round: 159 roles - 15 core, 7 language, 95 imported, 2
  handshake-verified MCP servers, 40 catalogued MCP servers); a factory whose slice defines nothing is
  present in the report as having no definitions and is never counted as a worker; the AI group
  (AutoGen, Semantic Kernel, CrewAI, LangGraph, MetaGPT, OpenHands, SWE-agent, …) is carried with its
  licence text and, where the licence permits, its content
- **Test method**: `shell/tests/test_sealed_environment.py`
  (`test_the_environment_is_reported_with_every_part`, `test_the_seal_carries_the_mcp_and_ai_groups_too`),
  `shell/tests/test_sealed_factory_runtime.py`, `tools/factory/imported_ai_factories.json` records
- **Evidence required**: the environment line (`159 agent roles`) and `capacity`'s `roles_by_source`

### AC-054 — "Any language" means the languages this machine can really build

- **Feature**: F-14
- **Given**: `sealed-factories/runtime/langkit.py`, which declares the toolchain each language needs
  (python, javascript, typescript, c, cpp, shell, web, go, rust, java, kotlin, csharp, ruby, php, sql)
- **When**: capabilities are asked for, and a project is scaffolded (`cli.py init --language … --check`)
- **Then**: every declared language carries a real scaffold with source, a build command and a test
  command; a scaffold is reported as verified only after its own build and tests were run and passed
  (`--check` → `verified: true`); a language whose toolchain is not installed is reported as missing
  with the binary it wanted and no version, and scaffolding it fails loudly rather than emitting a
  project that cannot build; nothing is claimed available that is not installed (this machine: 7
  available, 8 missing)
- **Test method**: `shell/tests/test_sealed_environment.py` (`LanguagePackTest`: every pack declares
  what it needs, the python scaffold builds and tests, a missing toolchain is reported and not faked,
  an installed toolchain reports its version)
- **Evidence required**: `init --language python --check` → `"verified": true`, and the capabilities
  list of available and missing languages

### AC-055 — A copy of the component works in another repository, with nothing from this one

- **Feature**: F-14
- **Given**: `sealed-factories/` copied into a foreign repository, with no file from this repository
  reachable
- **When**: the copy is asked to verify itself, list its tools and run its agents
- **Then**: `cli.py verify` verifies the copy's own seal through `runtime/sealcheck.py` (the
  component's own verifier, which imports nothing from outside the directory), `toolserver.py --list`
  serves the tools, and `cli.py run --root <that repository>` executes agents, writes its artefacts
  and its report into that repository's `.sealed/` state directory and passes every check; the
  control surface accepts its global options before or after the command, so it can be driven the
  obvious way; the sealer's verifier and the component's agree on the same bytes, including on a
  tampered payload
- **Test method**: `shell/tests/test_sealed_environment.py` (`PortabilityTest`: verify, run, changed
  module, added module, verifier agreement)
- **Evidence required**: this round's foreign-copy run (`[SEAL] OK … root efd960c1…` from
  `/tmp/foreign/component`, and `159 roles … executed 30 … 0 failed … 30/30 checks passed`)

### AC-056 — The seal covers the code that runs it, not only the payloads

- **Feature**: F-14
- **Given**: the manifest `sealed-factories/SEAL.json`, which records every payload hash and now also
  every runtime module and data file the runtime reads (`component_files`, 10 entries: nine runtime
  modules and `mcp-servers.json`)
- **When**: verification runs, before any run
- **Then**: a runtime module changed after sealing fails verification, a module added under
  `runtime/` that is not in the seal fails verification, a missing component file fails verification,
  and a run refuses to start when verification fails - so the component cannot be half-updated and
  still call itself sealed
- **Test method**: `shell/tests/test_sealed_factory_runtime.py`
  (`test_the_seal_pins_the_code_that_runs_it`), `shell/tests/test_sealed_environment.py`
  (the changed-module and added-module cases in `PortabilityTest`)
- **Evidence required**: the sealer's line (`component files pinned=10`) and the failure output of a
  deliberately modified copy

### AC-057 — Plug and work: no user intervention between the seal and the result

- **Feature**: F-14 and F-13 (the pipeline)
- **Given**: the sealed component in place and the pipeline that runs it
- **When**: `node scripts/conveyor_belt.js` runs, with no configuration step and no prompt
- **Then**: station 0 verifies the seal, detects the machine's models by probing their default
  endpoints, reads any API keys that happen to be in the environment, builds the environment (tools,
  MCP catalogue, agent roles, language packs), runs the swarm and asserts the result; with no model
  and no key it still passes; if the environment is not functional (too few tools, too few roles,
  a failed check or a failed step) station 0 fails instead of sealing it; removing the directory
  makes station 0 report `ABSENT` and the remaining six stations still pass
- **Test method**: `scripts/conveyor_belt.js` station 0, and
  `shell/tests/test_sealed_factory_runtime.py`
  (`test_the_component_runs_before_the_current_factories`,
  `test_the_belt_runs_the_sealed_set_first_and_survives_its_absence`)
- **Evidence required**: the belt's station 0 output and the run banner from a machine with nothing
  configured

### AC-058 — The environment executes programs without a shell

- **Feature**: F-14
- **Given**: `proc.run` and `git.command`, the two tools that execute something, and the 159 agent
  roles that use them
- **When**: a command is run
- **Then**: the command line is split into arguments and the program is executed directly; `&&` chains
  programs and stops at the first non-zero exit, and every other shell operator - a pipe, a redirect,
  a command substitution, a background job - is refused with the reason rather than interpreted;
  `git.command` refuses anything that would chain a second program onto the git call; a program that
  is not installed is a named failure; and source files are checked for validity by `code.syntax`
  (Python by AST, JavaScript through `node --check` with its arguments passed directly), which
  reports a file it could not check as unchecked rather than as clean
- **Test method**: `shell/tests/test_sealed_environment.py`
  (`test_the_program_runner_uses_no_shell_at_all`,
  `test_the_syntax_tool_reports_what_it_could_and_could_not_check`), plus factory-2's own security
  agent, whose `insecure_subprocess_shell` finding was the reason this changed
- **Evidence required**: the absence of `shell=True` in `sealed-factories/runtime/`, the refusal
  messages for each refused operator, and a `run_all.sh` result of `FACTORY 2 RESULT: PASS`

### AC-059 — Sealing is reproducible, so a clean checkout can verify what it clones

- **Feature**: F-13, F-14
- **Given**: the sealer `tools/factory/seal_factories.py` and the committed component
- **When**: the same records are sealed again, and the committed tree is verified
- **Then**: a re-seal of an unchanged tree writes byte-identical payloads - the archive container
  carries no clock (the gzip header is written with `mtime=0` and no filename) and no ownership or
  mode from the machine that ran it - so re-sealing is a no-op; the payloads on disk rebuild exactly
  from the records, and `SEAL.json`'s hash for each factory is the hash of the payload beside it, so
  a manifest committed without its payloads fails here rather than in a clean checkout
- **Test method**: `shell/tests/test_sealed_factory_runtime.py`
  (`test_sealing_an_unchanged_tree_would_reproduce_it_exactly`, which rebuilds all 149 payloads from
  their records), plus `test_every_payload_hash_in_the_seal_matches_the_file`
- **Evidence required**: the rebuild-and-compare run over every record, and the `[SEAL] OK` line from
  a clone of the pushed branch (not from the checkout that sealed it)

### AC-060 — GitHub capability is inside the seal, and every problem it was brought in to close is closed with evidence

- **Feature**: F-14 (the environment inside the seal)
- **Given**: the problems recorded at the end of round 12 - MCP servers that could not start, the
  instrumented-test failures on the emulator, and toolchains that are absent with no way to reason
  about them - and the sealed environment described in AC-049 and AC-056
- **When**: the sealed component is built and its tool registry is used
- **Then**: the registry carries six GitHub tools - `github.repo`, `github.file`, `github.search`,
  `github.issues`, `github.commits`, `github.pr` - that call the GitHub REST API with the credential
  in `GITHUB_TOKEN`/`GH_TOKEN`/`GITHUB_PERSONAL_ACCESS_TOKEN` when one exists and anonymously when
  none does; the credential is never returned in any reply, and the rate limit and whether the call
  was authenticated are reported instead; an API error surfaces GitHub's own message; a repository
  name or search kind that is not valid is refused before any call is made; a seventh tool,
  `github.probe`, reports whether the API is reachable from this machine - distinguishing a host
  that answered from a host that was absent (`reachable`, `status`, `reason`) - and returns instead
  of raising, so a run records a machine with no network as an observation rather than as a failure,
  and the six data tools are declared to the researcher role so an agent driving it with a model can
  call them, while no offline step calls one (`SEALED_GITHUB_API` points the tools at an enterprise
  instance); `SEALED_NO_NETWORK=1` switches outbound networking off for the tools that leave the
  machine - `net.fetch`, `net.http` and all seven `github.*` tools - each refusing at once and naming
  the switch as the cause, with the probe answering `disabled` rather than spending a timeout to
  discover what the operator already knows, and no request leaving the process; every MCP launch that
  could not be completed is recorded in
  `tools/factory/imported_mcp_servers.json` with the machine's own diagnosis in `launch_note`, and
  every launch that was completed records the exact command and the tools list the handshake
  returned, so the catalogue's counts are the number of servers actually driven through an
  `initialize` handshake and a tool listing; and the emulator stage proves the screen is awake and
  focused before the instrumented suite runs, reporting a warning when it is not
- **Test method**: `shell/tests/test_sealed_environment.py` (`GitHubToolTest`, eleven tests: the tools
  run against a local server answering the API's own shapes, refusals asserted separately, the probe's
  four outcomes - reachable, answered-but-wrong-base, unreachable and switched off - are each asserted,
  the switch is asserted against a server that records whether it was contacted, and the
  role/offline-step design is pinned), `test_the_seal_carries_the_mcp_and_ai_groups_too` (which requires every count in the
  sealed catalogue to equal the registry's, and every server marked verified to carry a command and
  a tool list), `shell/tests/test_androidtest_failure_report.py`
  (`test_the_stage_wakes_the_screen_before_it_runs_the_suite`), and the handshake harness
  `tools/factory/mcp_handshake.py` whose output those counts come from
- **Evidence required**: the sealed run's tool count and tool-step count, the researcher role's
  artefact recording `github.probe` with `available=True` on this machine and with `available=False;
status=0; reason=GitHub is not reachable from here: ...` when the API is pointed at a dead host, and
  `reason=network calls are disabled: SEALED_NO_NETWORK is set` when the switch is on, the handshake
  transcripts for the
  servers recorded as verified, the diagnosed `launch_note` for each server that refused, and the
  pre-flight `::warning` annotation from a run where the device did not come up
- **Honest limitation**: a server that completes the handshake but publishes no tools
  (`mcp-atlassian`, 0 tools) is counted as verified because the handshake is what was measured; it
  is named here rather than rounded up, and the report says the same; the catalogue's "verified
  here" figure counts catalogue entries (11) while the registry counts server names (16), and both
  are published rather than one standing in for the other; the rate-limit tier observed on this
  machine is the sandbox's own egress tier - an anonymous `curl` here is answered as an
  authenticated client - so it is not evidence about an unauthenticated client elsewhere, and all
  the tool claims about credentials are about whether the process sent a token. The emulator pre-
  flight is reliability hardening: the failing run `226b71c` was not reproduced, so the change is
  not claimed as a proven fix

### AC-061 — The belt still runs the sealed set first, with GitHub tooling inside it

- **Feature**: F-14 and F-13 (the pipeline)
- **Given**: `scripts/conveyor_belt.js`, station 0, which runs the sealed set before the current
  factories (AC-057)
- **When**: the belt is run on a machine with no model, no key and no network
- **Then**: station 0 runs the sealed environment to completion with the GitHub tools registered but
  never called, reports the tool count it observed, and the six following stations pass; a GitHub
  call is not attempted, so an offline machine is not a failure
- **Test method**: `scripts/conveyor_belt.js` station 0 and
  `shell/tests/test_sealed_factory_runtime.py`
- **Evidence required**: the belt banner `SEALED SET RAN + ALL 6 CONNECTED STAGES PASSED` and the
  station 0 line stating the tool count

### AC-062 — The set is grown by importing from many repositories, inside a licence wall that is never worked around

- **Feature**: F-14 (the sealed factory set) and F-01 (provenance)
- **Given**: `tools/factory/imported_factories.json`, `tools/factory/repositories.json` (whose
  `planned` list named nine repositories that had never been imported), the licence files under
  `sealed-factories/licenses/` and the classifier `tools/factory/license_text.py`
- **When**: each catalogued repository's own published licence text is re-read
  (`tools/factory/license_rescan.py`), further repositories are imported from GitHub
  (`tools/factory/discover_factories.py`), the plan is executed and the set is re-sealed and run
- **Then**: a licence is _widened_ only where the registry recorded none (GitHub answers
  `NOASSERTION` for a plain MIT text that lost its title) and a text that contradicts a recorded
  licence is escalated as `disagrees`, never silently overwritten; a repository whose text is
  copyleft, source-available, mixed with a documentation licence or absent altogether stays
  blueprint-only with no content carried, and that is stated rather than worked around; content is
  carried only under a licence the seal accepts; every repository the plan names has a recorded
  outcome in `tools/factory/plan_import.json` - imported, or refused with the reason (not on
  github.com, or no definition path in its own tree: CI configuration alone is not a factory); no
  repository enters twice, whatever the case of its name; and `RUN-REPORT.json` counts what is
  operational against what is not, including slices that carry content but define nothing
- **Test method**: `shell/tests/test_license_rescan.py` (the classifier's must-fail cases, the
  widen-only-from-nothing rule, escape from a disagreement, and every carried factory's licence text
  re-classified offline), `shell/tests/test_factory_discovery.py` (the factory-shape bar, CI-only
  trees refused, canonical licence names, every planned repository accounted for) and
  `shell/tests/test_imported_factories.py` (the registry's own contract)
- **Evidence required**: the re-scan summary (4 widened, 98 confirmed, 12 blocked, 22 with no
  licence, 1 withheld, 0 disagreements), the plan outcome table, the seal root
  `3b7d58ab1ae274ef63a3a5a2aabe0c2039d658d89de8c311e1a9420736ce7bea` and the `RUN-REPORT.json`
  counts from the run that followed it (112 `OPERATIONAL-PLAN`, 37 `NO-STRUCTURE`, 2 of those
  carrying content without definitions)

### AC-063 — The set grows on GitHub, and a grown set is refused unless it beats the ledger it already reached

- **Feature**: F-14 (the sealed factory set) and F-01 (provenance)
- **Given**: `.github/workflows/factory-growth.yml` (triggered when the growth pipeline itself changes,
  and by hand on dispatch), `tools/ci/factory_growth.sh`, the three registries, the sealed component, and
  the ledger inside
  `tools/ci/factory_growth_judge.py`, measured from the committed set (149 records, 112 operational,
  113 carrying content, 84 imported, 592 stages, 579 workers)
- **When**: the workflow runs on a hosted runner, with the live GitHub API available
- **Then**: in one job and in this order — every licence is re-read from its repository's own text, and
  a scan this run could not complete fails the job rather than reporting a clean one; further factories
  are imported from GitHub search and every repository a plan names is accounted for; only the records
  whose licence or slice changed are re-vendored, and a record left without a slice fails the job
  rather than being silently absent from the seal; the set is re-sealed and the seal verified; the
  sealed runtime runs in plan mode with no model and no keys; the judge accepts only a set whose sealed
  record count, imported count, operational count and carried-content count are at or above the ledger,
  whose summary agrees with the records it lists, whose content is carried only under a licence the
  sealer accepts, and whose only carried-but-silent records are the two reference records — anything
  else fails, and a fall is never reported as growth; the repository's own formatter runs and the whole
  of `shell/tests` passes before anything is committed; the commit stages only `sealed-factories/`,
  `tools/factory/`, `docs/` and `artifacts/` — the paths that job's own formatter may rewrite — and
  nothing else; and, because a push made with `GITHUB_TOKEN` triggers no workflow, the belt is
  dispatched on the commit that was pushed, so the commit a machine made is still verified by the belt
- **Test method**: `shell/tests/test_factory_growth.py` (the judge against synthetic sets — growth
  accepted and reported, a shrunken set, an operational count below the ledger, content stripped, a
  registry record with no slice, content under a refused licence, a third carried-but-silent record, a
  summary that disagrees with its records, a seal and run that describe different sets, and the two
  conditions that must warn without failing — plus the vendoring plan's decisions and the workflow's
  own ordering), and the workflow file itself
- **Evidence required**: the workflow run URL of the `Factory Growth (GitHub-native)` workflow with a
  green `Grow and verify the sealed factory set` job on the branch, the job's own `factory set:` summary
  line (the judge is what lets that step end green; its `factory growth judge: PASS` line is in the job
  log), and the commit the job pushed. Recorded on 2026-10-15: run `38699025254` on `aed00a1` and run
  `38702482811` on `650e830`, both eleven steps green, summaries `factory set: 155 records (118
operational, 37 without definitions), 119 carrying content under 133 licence texts` and `factory set:
161 records (124 operational, 37 without definitions), 125 carrying content under 139 licence texts`;
  commits `04cfc6f` and `a31f95b`; and belt runs `38699724589` and `38703154310` (`workflow_dispatch`)
  green on those commits including `Code Quality & Linting` (report §2.12)

## 2. Traceability summary

| Requirement category (Phase 2 checklist)  | Criteria                        |
| ----------------------------------------- | ------------------------------- |
| clean first launch                        | AC-001                          |
| normal navigation                         | AC-002, AC-003                  |
| core user journeys                        | AC-004 – AC-009                 |
| invalid input                             | AC-013, AC-014, AC-015          |
| empty state                               | AC-016 – AC-018                 |
| loading state                             | AC-019                          |
| error state                               | AC-020                          |
| offline behaviour                         | AC-009, AC-021                  |
| persistence after restart                 | AC-023 – AC-025                 |
| configuration changes / rotation          | AC-026, AC-027, AC-010          |
| permission denial                         | AC-028, AC-029                  |
| back navigation                           | AC-030, AC-031                  |
| accessibility labels                      | AC-032, AC-033                  |
| duplicate actions                         | AC-034 – AC-036                 |
| process restart                           | AC-037                          |
| corrupted / missing local data            | AC-038, AC-039                  |
| release APK installation and launch       | AC-044 – AC-047                 |
| imported factories (sealed, pre-pipeline) | AC-048                          |
| security invariants                       | AC-021, AC-022, AC-040 – AC-043 |
| MCP servers inside the seal               | AC-049                          |
| one gateway for models and keys           | AC-050                          |
| offline-only operation                    | AC-051                          |
| swarm scale and honest limits             | AC-052                          |
| multi-agent coverage, AI factories        | AC-053                          |
| language coverage                         | AC-054                          |
| portability and self-sufficiency          | AC-055                          |
| the seal covers the runtime               | AC-056                          |
| no user intervention                      | AC-057                          |
| no shell anywhere in the environment      | AC-058                          |
| reproducible sealing                      | AC-059                          |
| GitHub capability inside the seal         | AC-060, AC-061                  |

## 3. Test-method notes (no test is weakened to pass)

- **AC-021 (`SslDecisionTest` + instrumented `SslPolicyTest`)**: `android.webkit.SslErrorHandler`
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
- **AC-011 / AC-032**: adapter/UI coverage that needs a real `Context` runs as instrumentation tests;
  the pure display logic (`DisplayText.titleFor`) is additionally unit-tested on the JVM so the
  assertion exists in both layers.
- Instrumented tests run on a real Android emulator in CI (`connectedDebugAndroidTest`). They are not
  skipped, not `@Ignore`d, and their result XML is committed as evidence.
