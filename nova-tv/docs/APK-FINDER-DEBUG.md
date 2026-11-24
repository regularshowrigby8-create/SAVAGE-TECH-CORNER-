# APK finder debug session

**Question:** did the finders get fixed, do they crawl the public web, and do
they actually store anything in a current-source APK?

**Honest status:** source-level fixes and a fail-closed debug harness are in the
worktree. A static/offline `PASS` is not an APK result. The local sandbox has no
JVM, Gradle, Kotlin compiler, Android SDK, `aapt2`, `apksigner`, `zipalign`,
APK binary, emulator or unrestricted outbound network, so the current-source
APK, its dex, installation, runtime crawl and UI-button behaviour can only be
proven by the GitHub Actions jobs described below. Until those jobs finish, the
answer to "do they crawl and find anything?" is **not yet observed on device**.

## What was fixed in source

1. `DiscoveryEngines.kt` GitHub issue-search URLs now include `is%3Aissue`.
   The previous URLs returned HTTP 422 from GitHub, so five engines were dead.
2. `DiscoveryEngines.kt` unwraps `uddg=`/`u=`/`url=` style redirect parameters
   before normalising a discovered URL. Search-engine redirects used to be
   sorted as the search host and discarded.
3. `PipelineLooker.kt` no longer treats an add-on's missing homepage as a
   failure, reads the add-on manifest, builds catalogue endpoints from its
   declared `catalogs`, and only requests a stream endpoint when the manifest
   advertises `stream`. `PipelineExtractor.kt` counts real JSON `metas` and
   `streams` arrays as catalogue evidence, not only HTML anchors.
4. `crawl_probe.py` applies the same redirect unwrapping, GitHub issue fix,
   manifest-driven endpoints and JSON catalogue counting. It throttles GitHub
   API requests to avoid the search endpoint's secondary rate limit, prioritises
   stable public endpoints discovered in index responses for its bounded look
   budget, and stops after three verified sites. A local profile never claims
   CI verification.
5. `FinderProbeActivity.kt` is a `src/debug`-only, adb-launched activity. It
   runs both production `PipelineRunner`s in `pipeline` mode and the exact
   `SourceEngine.findWebsites()` / `fillServers()` methods in `ui` mode, then
   writes JSON, `FinderTrace.dump()` and a `.done` marker into app-private
   storage. The activity is registered only in the debug manifest.
6. `NovaViewModel.kt` logs `Find sites tapped` / `Find sites result=...` and
   `Fill addons tapped` / `Fill addons result=...` under `NovaFinderUi`, so a
   real UI tap can be observed in logcat rather than inferred from a toast.
7. `nova-tv-android.yml` now has explicit `finder-crawl-probe` and
   `finder-emulator-smoke` jobs. They fail closed when an engine fetch,
   verified live endpoint, APK install, debug probe, UI button, persisted pool
   or report artifact is missing.

## What each CI job proves

| Job | Real action | Evidence |
|---|---|---|
| `build` | compiles/lints both flavours, runs both flavour unit tests, assembles four APK cells, checks APK ids/signatures/alignment/dex markers | workflow step output, four-cell JSON, uploaded APKs |
| `finder-crawl-probe` | fetches all 65 round-0 public-index URLs, parses discovered hosts, then performs bounded live manifest/catalogue/stream and byte probes | crawl-probe JSON in the CI evidence artifact |
| `finder-emulator-smoke` | installs the current mobile debug APK, runs `pipeline` and `ui` debug modes, pulls JSON/traces/logcat/pool files, navigates More → Sources → Find sites with UIAutomator | CI artifact with JSON, trace, logcat, pool, UIAutomator and screenshot files |
| report step | renders the committed Markdown report and copies the three JSON payloads | committed `finder-debug-report.md` and copied JSON payloads |

The debug probe asserts, rather than prints, that:

- the WebView renderer context is available;
- both pipeline reports carry the `nova-finder-pipeline-v3` marker and fetched
  engine surfaces;
- at least one report reached stages `12-verify` and `13-store`;
- `newPipelineSites > 0` for pipeline mode;
- `uiButtonAdditions > 0` and `newUiSites > 0` for UI-mode methods;
- the real `Find sites` button reached `NovaViewModel` and returned a result;
- persisted `website_pool.json` and `server_pool.json` contain entries.

## Reproduce locally where possible

A bounded local index fetch (no candidate look, no APK claim):

```bash
python3 tools/nova/crawl_probe.py \
  --profile local --round 0 --max-look 0 --timeout 12 --workers 4 \
  --output /tmp/nova-crawl-local.json
```

The `local` profile deliberately reaches only the sandbox's allowed public
index hosts. It proves that engine URLs were fetched and candidates were parsed;
it does **not** prove that candidate sites were verified, that the Android app
crawled, or that an APK installed.

The full CI-equivalent crawl needs unrestricted public network access:

```bash
python3 tools/nova/crawl_probe.py \
  --profile ci --round 0 --max-look 120 --timeout 8 --workers 16 \
  --output tools/nova/evidence/ci/crawl-probe.json
```

Manual debug entry points after installing a mobile debug build:

```bash
adb shell am start -n dev.nova.mobile/dev.nova.tv.finder.diag.FinderProbeActivity --es mode pipeline
adb shell am start -n dev.nova.mobile/dev.nova.tv.finder.diag.FinderProbeActivity --es mode ui
adb exec-out run-as dev.nova.mobile cat files/finder-debug/pipeline.json
adb exec-out run-as dev.nova.mobile cat files/finder-debug/pipeline.trace.txt
```

## What remains unproven

- Playback of a discovered stream.
- 10,000 live sites. The deterministic unit test proves the capacity path with
  fixtures; it is not a live-web claim.
- Installation on a physical TV/mobile device.
- Any result from the historical `45398507630` run. That run predates these
  fixes and its artifacts were not downloaded into this sandbox.
- Any claim that a local static/offline `PASS` is an APK/runtime result.

A red or incomplete CI report is a debug result, not a reason to weaken the
assertions. The committed report explicitly marks missing evidence as
`MISSING` and never renames a partial run to success.
