# Nova finders — shared TV/mobile pipeline v3

Both product flavours use the same finder code under
`android/app/src/main/java/dev/nova/tv/finder`. There is no finder copy under
`src/tv` or `src/mobile`; `nova-tv-android.yml` runs both flavour unit-test
tasks. The two pools are still separate: `WebsitePool` keeps up to 10,000
streaming sites and `ServerPool` keeps up to 10,000 validated add-on/server
records. `servers_pool.json` and `addons_pool.json` remain the persisted stores
that the UI reads.

## Discovery is generated, not seeded

`finder/pipeline/DiscoveryEngines.kt` defines 65 public-index engine
configurations: 35 website engines and 30 add-on engines across eleven endpoint
families (GitHub repositories and issues, Hacker News/Algolia, certificate
transparency, urlscan, Wayback CDX, Bing RSS, DuckDuckGo HTML, npm, PyPI and
Reddit JSON listings). They are topic/query configurations, not a curated list
of streaming sites. No engine bypasses authentication, CAPTCHAs, paywalls, DRM
or private access controls. The shared endpoint hosts are parsed with an
explicit pipeline kind, so an add-on response cannot be stored as a website
candidate.

The registry has no hardcoded site seeds. It asks public indexes topic
questions and rotates query modifiers by round. A configured engine is
unrestricted in the sense that discovery is not moderated by an allowlist; the
later sorter and verifier are the explicit quality gates. Engine availability
and live site reachability are never assumed by the offline audit.

## The fourteen stages

`finder/pipeline/PipelineRunner.kt` executes and records all fourteen stages in
`finder/pipeline/PipelineModels.kt`:

1. `01-plan` — state the engine floor, target and budget.
2. `02-engines` — generate one explicit URL per configured engine.
3. `03-fetch` — fetch each public index; a failure is a recorded rejection.
4. `04-parse` — parse only URLs present in the index response.
5. `05-normalise` — require an absolute public host and a canonical origin.
6. `06-dedupe` — remove redundant hosts and repeated inspections.
7. `07-sort` — `finder/pipeline/PipelineSorter.kt` rejects ads, trackers,
   static assets, CMS handlers, private hosts, public-index hosts, torrent
   links, irrelevant corporate/CDN hosts and stub shapes. Acceptance here only
   buys an inspection.
8. `08-look` — `finder/pipeline/PipelineLooker.kt` behaves like a viewer: open
   and render a website, run title searches, and for an add-on read a manifest,
   catalogue response and stream response. It also records failed renders and
   failed endpoints instead of treating them as empty catalogues.
9. `09-classify` — require title-search evidence, at least 40 catalogue items
   and an observed server/stream endpoint.
10. `10-extract` — `finder/pipeline/PipelineExtractor.kt` extracts only URLs
    observed in the DOM, responses or browser network log; no URL shape is
    guessed.
11. `11-probe` — byte-level probe of the extracted source through the shared
    HTTP session.
12. `12-verify` — `finder/pipeline/PipelineVerifier.kt` rejects fake, dead,
    ad/tracker, torrent, bot-wall, irrelevant corporate MP4 and unsuitable
    servers with explicit reasons.
13. `13-store` — verified sites are written through
    `finder/pipeline/PipelineAdapters.kt` into the existing UI pools, and the
    store result is checked.
14. `14-audit` — `finder/pipeline/PipelineModels.kt` writes a report whose
    status is `TARGET_MET` only when there are no integrity errors, the run did
    not stop on budget, all stages ran and the measured verified count is at
    least 10,000. `PARTIAL`, `BUDGET_STOPPED` and `DEGRADED` are not green.

The 10,000 number is a target and a deterministic capacity proof, not a claim
that 10,000 live streaming sites were reached from this sandbox.
`android/app/src/test/java/dev/nova/tv/finder/pipeline/PipelineRunnerTest.kt`
uses registry-shaped fixture responses for 10,000 generated hosts and proves
that both the website and add-on pipelines can reach the target in two bounded
runs. Local Android/Kotlin execution is not available in this sandbox; CI runs
that test for both flavours.

## Transport, renderer and older-report fixes

`FinderBrowser` renders through Android's WebView, not an always-plain-HTTP
stub. `FinderHttp` keeps a session cookie jar, checks allowed URLs and records
tripped hosts. `ServerRecord.toSiteUrl()` uses the referer origin before falling
back to the media origin; the Python audit asserts the function body, not just a
marker. The old report's single-batch harvest, missing visited guard, ad-frame
extraction, cookie loss, ignored request headers, leaked position coroutine,
`isPlaying()` mistake, keystore task naming, signing task names, missing Media3
modules and lint detector crash are covered by the mutation-tested audit in
`nova/translated_check.py`.

`FinderTrace` still records render, HTTP fallback, probe and extraction events;
the pipeline adds engine/stage/verified/rejection evidence. A failed browser
render is now logged as a fallback or an empty fallback instead of silently
looking like an empty catalogue.

## Verification

`tools/nova/strict_verify.py` is the contract-driven gate used before accepting
changes to this build; `tools/nova/contracts/pipeline-v3.json` is the current
contract and `tools/nova/evidence/pipeline-v3.strict.json` is the explicit local
PASS record. `tools/nova/check_artifacts.py` requires exactly one APK in each of
the four flavour/build cells and checks package id, signature, alignment, dex
markers and source markers, including `nova-finder-pipeline-v3`. Neither local
toolchain absence nor a green CI step is treated as proof; the exact scope and
the four CI-only commands are listed in `tools/nova/README.md`. The APK artifacts
from successful run `45398507630` are identified by metadata; the binaries were
not downloaded in this sandbox and are not claimed to have been locally verified
here.

## APK runtime debug

The static gates above do not answer whether the installed APK crawls and stores
anything. The APK-focused debug harness, source fixes, CI jobs and evidence
contract are documented in `APK-FINDER-DEBUG.md`. It uses a debug-only activity,
real public-index crawl probes, an emulator smoke test, UI-button logcat and
persisted pool dumps. Runtime results are reported only from the committed
`finder-debug-report.md` after the current-source CI run.
