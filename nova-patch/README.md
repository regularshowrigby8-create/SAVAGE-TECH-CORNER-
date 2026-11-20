# Nova server finder — drop-in patch

This directory is a **drop-in patch**, not a copy of the app. Nothing here is part
of the current branch's build; `tools/apply_nova_patch.py` copies these files over
a checkout of `arena/nova-final-debug` (tip `3347198`).

```bash
python3 tools/apply_nova_patch.py --target /path/to/nova --check   # read-only
python3 tools/apply_nova_patch.py --target /path/to/nova --write   # apply + back up
```

Backups go to `<target>/.nova-patch-backup/`, never beside the file: a copy under
`android/app/src/` gets compiled and audited as real code.

## What changed, and why

| File | Change |
|---|---|
| `finder/pool/ServerHarvester.kt` | Rewritten. 6 sources run concurrently over a shared gate (24 requests in flight, 3 per host). Each source reports `isDone()`; the loop stops only when **all** are done *and* none is waiting out a throttle. Source failures are counted (`HarvestState.failures`), never reported as "nothing found". |
| `finder/pool/ServerPool.kt` | `addAndCount()` returns (added, size) so a full loop can tell "already known" from "no room"; `PoolState.free/filling/progress`; `working()` lists only servers that have actually answered. |
| `finder/SourceEngine.kt` | The `POOL_EMPTY_ROUNDS = 4` give-up loop is gone. `syncPoolFill` drives `continuousFill` until 3000; `stopPoolFill()` exists; `scramblePool` pauses the fill before taking the lock instead of deadlocking on it. |
| `ui/screens/NovaSources.kt` | Shows the real pool count, a measured progress bar, and the harvester's own counters. Fill becomes **Stop** while filling. |
| `ui/screens/NovaViewModel.kt` | Exposes `poolFilling` / `harvest`; errors and empty results are worded differently. |
| `mobile/.../MobileSources.kt` | Same status line and Stop control on the phone layout. |
| `test/.../ServerPoolFillTest.kt` | **New.** 15 JVM tests: fill stops at capacity, stops when exhausted, dedupes across sources, counts requests. |
| `tools/server_crawler.py` | **New.** Standalone threaded crawler for addon manifests, continuous until `--target` (default 3000) or exhaustion, Ctrl-C writes what it has. Emits the exact JSON `ServerPool` reads. |
| `tools/site_crawler.py` | **New.** The second crawler: finds streaming *sites* and writes `site_pool.json`. A site enters the pool only after a real search on it returns title links, so a parked domain cannot get in. |
| `finder/website/SitePool.kt` | **New.** The second pool, 3000 sites, imported by host, offered to `WebsiteFinder` proven-first. Separate from `ServerPool` because a site and a manifest are not the same thing. |
| `data/store/AtomicFileStore.kt` | **New.** Both pools save through one atomic writer. The old fallback rewrote the real file when rename failed -- the branch that runs when the filesystem is already unhappy. |
| `NovaApplication.kt` | Constructs the site pool and imports the pool bundled in assets on first launch, off the main thread. |
| `test/.../SitePoolTest.kt` | **New.** 12 JVM tests: dedupe by host, refusal of unsearchable entries, malformed documents reported, the separate 3000 ceiling, proven-first ordering. |
| `test/.../ServerPoolFillTest.kt` | **New.** 15 JVM tests: fill stops at capacity, stops when exhausted, dedupes across sources, counts requests. |

## The crawl, concretely

Sources, in the order they are tried: `stremio-addons.net` (60 pages, manifests
inline — 1 request per 100 addons), `api.strem.io/addonscollection.json`,
Cinemeta's community/official catalogues plus the catalogue graph they name,
GitHub search (8 queries × 4 pages), GitHub Pages hosts and homepages, and the
community README lists.

Raised from the old values: `CONCURRENCY 8 → 24`, `BUDGET_MS 45 000` removed
(per-request 15 s timeout instead), `STREMIO_PAGES 8 → 60`, `GITHUB_PAGES 10 → 4
per query × 8 queries`, README budget 25/round, 60 manifests/round.

**The honest limit:** GitHub's unauthenticated search allows 10 requests/minute.
The public directories list far fewer than 3000 *unique* servers, so 3000 depends
on GitHub, GitHub Pages, homepages and the catalogue graph. If the crawl cannot
reach 3000 it says so with per-source yield (`--verbose` names every server as it
is found) rather than reporting success. `--merge existing.json` adds to a pool
you already have. `GITHUB_TOKEN=...` raises the quota.

## What is verified, and what is not

Verified here (static only — no JDK, no SDK, no device in this environment):

- 87 Kotlin files scanned, **0 errors**; **0 findings inside any patched file**.
- `tools/verify_no_false_green.py`: **62 pass, 0 FAIL, 1 skipped** — every rule
  fires on a planted defect and stays quiet on correct code. The 1 skip is the
  broad "correct code" corpus, which is exempt by name.
- The 19 crawler tests pass offline.
- The patch applies cleanly to `3347198` and is byte-identical afterwards.

**Two pools, two ceilings.** `server_pool.json` holds up to 3000 addon manifests;
`site_pool.json` holds up to 3000 verified streaming sites. They are never merged,
and each has its own count on the Sources screen. A site is resolved by
`WebsiteFinder` (page -> up to two player frames -> direct `.m3u8`/`.mp4`/`.mpd`);
an embed URL is never shown or played as a source.

**Not verified, and cannot be from here:** it has never been compiled. The CI
workflow at `3347198` was green for the *unpatched* tree, so the first real test
of this patch is a CI run. Expect possible compile fixes; the logic is tested,
the Kotlin is not.

## A correction to an earlier claim

An earlier note said the TV build failed on `NovaHome.kt:138 Unresolved
reference 'stalled'`. That is real, but it is from commit `e19a206` — the tip
`3347198` already declares `stalled` and its `nova-tv Android` run is green. No
source change was made for it, because the current code has no such defect.

Separately, the repo's own `docs/BUILD-STATUS.md` states the APK "has never been
installed on a device or an emulator". A crash after the server finder therefore
cannot be reproduced or confirmed from this environment; it needs a logcat trace.
