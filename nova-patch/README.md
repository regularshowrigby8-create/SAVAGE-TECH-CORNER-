# Nova server finder — drop-in patch

This directory is a **patch**, not a full app checkout. It targets the Nova
sources at `arena/nova-final-debug` commit `3347198`. The patch and the two
pools stay on this branch under `nova-patch/`; no partial top-level `nova-tv/`
tree is added here.

```bash
python3 tools/apply_nova_patch.py --target /path/to/nova --check  # read-only
python3 tools/apply_nova_patch.py --target /path/to/nova --write  # apply
```

The applier copies only `nova-patch/nova-tv/`, verifies every destination against
`BASELINE.json`, and refuses the entire plan if any file has drifted. It never
copies this README, diagnostics, counts, or `pools/` over the target checkout's
root. Replaced files are backed up in `<target>/.nova-patch-backup/`. An I/O
failure during copying can still leave a partially applied patch; retain the
backups. To apply a newer patch to a previously patched tree, use a clean checkout
of the recorded baseline or resolve the conflicts deliberately.

## Finder and pools

- `ServerHarvester` runs several discovery sources concurrently and reports
  actual progress and failures. Stop, fill and scramble are exposed on both UIs.
  A coordinator serializes replacement fills and one-shot scrambles while
  cancellation unwinds, and a stop during a scramble stays stopped. This is a
  race-prevention measure, not a demonstrated fix for the reported crash.
- `server_pool.json` holds up to **3000** addon manifests; `site_pool.json`
  holds up to **3000** sites. They have separate capacities and are imported
  from separate assets on an IO thread. Bundled imports preserve on-device data.
- Site imports are deduplicated by host and batched into the saved-site list;
  manually saved sites are not replaced. The resolver tries a bounded batch,
  prioritizing sites that have produced streams; removed sites are not tried.
- New site candidates must produce a matching, same-host title link that is not
  also present in a nonsense-query control response, plus a potential player or
  direct-media marker on a sampled title or episode page. These conservative
  **heuristics are not proof of playback**, continued availability, or licensing;
  they may also reject usable sites. Legacy entries from the weaker check are
  retained as `pending_recheck` candidates, not counted as checked entries.
  The resolver only emits direct media, not embed pages. URL checks reject
  obvious private literal addresses, including redirects, but do not independently
  validate every hostname's DNS resolution.
- Pool files are persisted through an atomic replacement attempt. A failed write
  keeps the previous file and reports an error rather than overwriting it.

## GitHub Actions

`nova-patch-build.yml` applies the patch to a fresh Nova checkout, compiles both
debug flavors, propagates unit-test failures, and assembles both APKs. It uploads
`nova-debug-apks` only after both APKs match the current nonempty pool assets
and the site records carry current-method evidence. Run
[44805110661](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/44805110661)
compiled, ran the tests, assembled and uploaded the TV and mobile debug APKs.
That run used commit `916946a` and **empty initial bundled pool assets**. Later
runs must be checked separately for their status and pool contents. Crawl run
[44810790179](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/44810790179)
published 506 manifests and 31 *legacy-heuristic* site candidates, but its
follow-on build failed before APK assembly and its emulator step did not run.
Some legacy site candidates were false positives; do not treat that 31 as a
count of usable streaming sites.

Crawl run [44886402950](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/44886402950)
published **518 manifest entries and 1 current-heuristic site candidate**,
with 30 legacy candidates still pending recheck. It passed both Android flavor
unit-test suites and assembled both debug APKs; their packaged assets matched
those published pools byte-for-byte. Its KVM setup failed, so the emulator did
not run and its distribution gate and APK upload were skipped. The site check
did not establish playback or licensing, and the manifest count is not a fresh
liveness test of every retained entry.

The later independent [patch build 44887387017](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/44887387017)
passed compilation, both unit-test suites, assembly and the nonempty packaged-pool
check, and uploaded `nova-debug-apks`. It did not perform an emulator test.
[Crawl 44887387025](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/44887387025)
again built and byte-checked 518/1 entries, but the emulator action failed before
it produced a smoke summary. KVM was available after permissions were adjusted;
the exact action error was not captured. The workflow now requests the API 34
Android TV `x86` image and records SDK setup diagnostics.

[Crawl 44888502419](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/44888502419)
then passed its Android tests, byte-for-byte pool checks, distribution gate and
bounded emulator smoke test. Each flavor imported 518 manifests and 1 site and
remained alive for 90 seconds after server filling started on an Android TV
emulator. The [TV and mobile debug APKs](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/44888502419/artifacts/14700877985)
are in the `nova-crawled-debug-apks` artifact (14-day retention). This is not a
playback test or a diagnosis of the separate device-specific crash.

`nova-pool-crawl.yml` runs the threaded manifest and site crawlers on GitHub
Actions. It validates and publishes both JSON files, copies them to the two app
assets, and reports the real counts and per-source evidence in
`POOL-COUNTS.md` and `pools/counts.json`. It accepts a below-target crawl as a
bounded result, but a crawler crash, invalid output, or failed push is a failure.
The workflow builds both APKs against those exact assets and attempts a bounded
emulator smoke test, requiring both pool-import markers as well as the fill
start marker. It tries KVM when available and otherwise attempts a slower
software emulator; an emulator boot failure is not a pass. An empty checked
site pool may be used for smoke testing, but fails the distribution gate;
`APK-VERIFICATION.md` and `EMULATOR-SMOKE.md` record the respective outcomes.
Push runs use a short budget for validation; manual and scheduled runs can use
longer budgets.

The schedule is hourly **only after the workflow exists on the repository's
default branch**: GitHub Actions does not run scheduled workflows from this
feature branch. On the current branch, pushes that change the crawler or its
workflow start runs; a continuous scheduled crawl here is not established. Each
run has a finite time budget and may finish below 3000. Repeated runs seed the
previous committed pools, but the target is not a guarantee.

## Verification and limitations

`tools/verify_no_false_green.py` exercises the debugger's rules with planted
defects, checks the supplied source corpus is not empty, and checks assets and
invalid input handling. These are useful regression checks, not a proof that all
bugs, dead controls or hallucinations are detected. `tools/debug_nova.py` reports
static findings and can inspect supplied APKs or a logcat trace.

The observed post-finder crash has **not** been reproduced on a device or
emulator. Compilation, unit tests and static checks do not establish its root
cause. A logcat from the affected TV or mobile device is still needed to diagnose
that runtime failure. Neither pool has been demonstrated to reach 3000; see the
latest recorded run rather than treating the target as a measured count.
