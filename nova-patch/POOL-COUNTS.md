# Nova pool crawl — measured results

Two pools, two 3000 ceilings, run on a GitHub runner by
`.github/workflows/nova-pool-crawl.yml`. These are the numbers from run
44788418728 on 2026-11-20, not targets.

| pool | entries | ceiling | time |
|---|---|---|---|
| `server_pool.json` (addon manifests) | **502** | 3000 | 45 min budget, used |
| `site_pool.json` (streaming sites) | **83** | 3000 | 45 min budget, used |

## What the site crawl rejected

| reason | count |
|---|---|
| no homepage (dead, parked, or refusing) | 1290 |
| homepage loaded, search returned nothing usable | 1266 |

2,639 candidate hosts were tried before 83 passed. That ratio is the point of the
verification step: without it the pool would hold 2,722 entries, roughly 2,600 of
which are dead, and the app would spend a minute per title failing on them.

## Why this is below 3000, and what would raise it

This is a **bounded** result, not an exhaustion claim. Three separate limits:

1. **Time.** Both crawlers stopped at the 45-minute ceiling with work left. The
   GitHub search rotation had not finished its pages.
2. **Source list.** The site crawl profiles hosts named by ten public lists and
   ten GitHub queries. There are more lists; adding them is the cheapest win and
   needs no code change (see `LIST_SOURCES` / `GITHUB_QUERIES`).
3. **The bar.** A site must answer a real search with title links. That is what
   makes an entry usable, and it is why the yield is 83 and not 2,639.

To go further: raise `minutes`, add list sources, or run it again later — GitHub's
index and public lists change, so a re-run finds different hosts.

## The manifest pool stopped at 502 for a different reason

`server_crawler.py` is rate-limited by GitHub's unauthenticated search allowance
(10 requests/minute, 30/minute with a token). It also stops when a whole round
finds nothing new. 502 is what the public, keyless addon directories and GitHub
yield in 45 minutes. A `GITHUB_TOKEN` secret would raise this materially; it is
not configured, and the crawler says so rather than pretending.

## Honest summary

- 3000 manifest entries: **not reached** (502).
- 3000 verified streaming sites: **not reached** (83).
- Neither number is a claim of failure or success — they are what the public
  internet yielded under the stated bar in 45 minutes.
