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
- `server_pool.json` holds up to **3000** addon manifests; `site_pool.json`
  holds up to **3000** sites. They have separate capacities and are imported
  from separate assets on an IO thread. Bundled imports preserve on-device data.
- Site imports are deduplicated by host and batched into the saved-site list;
  manually saved sites are not replaced. The resolver tries a bounded batch,
  prioritizing sites that have produced streams; removed sites are not tried.
- Newly crawled sites must return a title search with at least three distinct
  same-host content links, in addition to the score threshold. This is a **search
  check**, not proof that playback works, that a site remains online, or that its
  content is licensed. The resolver only emits direct media, not embed pages.
  URL checks reject obvious private literal addresses, including redirects, but
  do not independently validate every hostname's DNS resolution.
- Pool files are persisted through an atomic replacement attempt. A failed write
  keeps the previous file and reports an error rather than overwriting it.

## GitHub Actions

`nova-patch-build.yml` applies the patch to a fresh Nova checkout, compiles both
debug flavors, propagates unit-test failures, assembles both APKs, and uploads
`nova-debug-apks`. Run
[44805110661](https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/actions/runs/44805110661)
compiled, ran the tests, assembled and uploaded the TV and mobile debug APKs.
That run used commit `916946a` and **empty initial bundled pool assets**. Later
runs must be checked separately for their status and pool contents.

`nova-pool-crawl.yml` runs the threaded manifest and site crawlers on GitHub
Actions. It validates and publishes both JSON files, copies them to the two app
assets, and reports the real counts and per-source evidence in
`POOL-COUNTS.md` and `pools/counts.json`. It accepts a below-target crawl as a
bounded result, but a crawler crash, invalid output, or failed push is a failure.
A later workflow revision also builds and checks APKs against the exact assets
from that crawl; check the particular run before relying on that artifact.

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
