"""Render the human-readable report from the shipped, non-executable registry."""
import collections
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
data = json.loads((ROOT / 'app/src/main/assets/searchhh-codebases.json').read_text())
rows = data['entries']
counts = collections.Counter(r['repositoryStatus'] for r in rows)
lines = ['# Crawler codebase audit — 2026-09-29', '',
'**All original entries #1–100 are retained, plus 20 additions (#101–120).** Original names and URLs are preserved, including unresolved entries. The existing **110 portal seeds are unchanged**. Portal sources, software codebases, dependencies and working adapters are separate counts.', '',
'## What was actually checked', '',
'GitHub repository API metadata, canonical redirects, source revisions and detected license metadata were fetched with `gh`. Non-GitHub source references were checked against project/SourceForge/GNU/Apache pages. Repository metadata is not a build test; archived=false is not a maintenance/security guarantee; an SPDX detector result is not a complete licensing review.', '',
'Lookup outcomes: ' + ', '.join(f'**{v} {k.replace("_", " ")}**' for k,v in counts.items()) + '.', '',
'No arbitrary project code was executed during the audit. The Android app ships the reference registry and its own existing library-based collection path, not 120 third-party executables.', '',
'## All five requested ignore options are retained', '',
'`requestedIgnoreProfile` preserves robots.txt, rate limits, politeness delays, max depth and domain scope as requested. **`requestedProfileIsRuntimeConfig` is false.** These are recorded requirements, not secretly enabled switches. Each entry has five `configurationSupport` fields. Unverified stays unverified; non-crawler libraries do not magically acquire crawler options.', '',
'Code-level checks confirm configurable client settings in Scrapy, Colly, Katana and GoSpider. In particular, GoSpider’s `--robots` means discover URLs from robots.txt, not necessarily obey/ignore robots rules. Changing client pacing cannot disable a remote server’s enforced quotas. Public runtime retains robots, server cooldowns, resource bounds, host-scoped follow-up and private-IP protection.', '',
'## Exact unresolved identities — retained, not omitted', '',
'- **#50 nicmart/PHPCrawler:** GitHub 404; original identity not resolved. A PHPCrawl alternative is recorded without claiming it is the same project.',
'- **#78 s0md3v/urlscan:** GitHub 404; no matching repository found under that owner. Not silently replaced by urlscan.io.',
'- **#92 spelling correction:** same-owner `z0m31en7/Uscrapper` was subsequently verified by GitHub metadata and README. It is recorded as the probable intended project, with the malformed original URL preserved.', '',
'## Important corrections', '',
'- #13 StormCrawler resolves to `apache/stormcrawler`; #17 Scrapy-Splash resolves to `scrapy-plugins/scrapy-splash`.',
'- #21 BUbiNG source is `LAW-Unimi/BUbiNG`, linked by the official LAW software page. That page says GPL while GitHub detects Apache-2.0: inspect the chosen revision before importing.',
'- #36 corrected `madeindjs/spider` resolves to `spider-rs/spider`; the submitted `spider_rust` path did not resolve.',
'- #40 DataparkSearch source located under `Maxime2/dataparksearch`; #41/42 historical sources located on SourceForge.',
'- #49 Droids retired on 2015-11-01; Apache documents its historical SVN URL, not the submitted GitHub path.',
'- #83 is a Kali **packaging repository**, not proof that original Java source is present. The verified tree includes a bundled JAR and an upstream branch.',
'- #117 Norconex’s old repository redirects to `Norconex/crawler`.',
'- Xapian, Meilisearch, Tinysearch, Tantivy, Lucene, Solr and Sphinx are primarily search/index components; parsers, downloaders, asset discovery and secret scanners have separate roles. All remain listed.', '',
'## Original list and additions', '',
'“Source reference” means a repository/source archive or an upstream-linked source location. Downloads/checkout/compilation were not performed for every project. See JSON for exact revisions, submitted language, descriptions and field-level configuration evidence.', '',
'| # | Project | Role | Source reference / outcome | License metadata | Integration |',
'|---|---|---|---|---|---|']
for r in rows:
 link = r.get('sourceUrl') or r.get('canonicalUrl')
 label = f'[Source]({link})' if link else 'Unresolved — submitted URL retained in JSON'
 if r.get('archived'):label += ' · archived'
 if r.get('repositoryStatus') == 'packaging_repository_verified':label += ' · packaging, not original source certification'
 lines.append(f"| {r['number']} | {r['name']} | {r['category']} | {label} | {r['license']} | {r['integration']} |")
lines += ['', '## Runtime construction in 0.4', '',
'- **Android:** searchable/exportable complete registry in Sources → Codebases; a read-only authenticated `list_codebases` MCP tool. It does not accept codebase IDs as portal IDs or execute catalogue entries.',
'- **Phone-native collection:** existing OkHttp + Jsoup + crawler-commons, extended with bounded JSON-LD opportunity extraction, relative Atom links, same-host sitemap discovery (one index child and one discovered detail per source/pass), quoted-query matching and provenance. No AI crawling or on-device inference.',
'- **Optional Python backend:** existing Scrapy + Parsel selectors now reuse **Extruct 0.18.0** and **Trafilatura 2.2.0** for structured metadata and article text from already-fetched HTML. These are real integrations, but not Android-hosted Python runtimes and not additional independent crawlers.',
'- **Not constructed:** automatic execution adapters for all 120 projects, a multi-language runtime farm, arbitrary deep/global crawling, challenge/login bypass, secret harvesting or security scans of opportunity sites. Catalogue inclusion is not a claim those features exist.', '',
'## Reproduction and limits', '',
'- `tools/crawlers/codebases.tsv` preserves all submitted URLs and addition identities.',
'- `tools/crawlers/audit_codebases.py` performs bounded GitHub metadata lookups; `overrides.json` holds explicitly sourced corrections and configuration findings. Run deliberately: it refreshes the dated evidence snapshot.',
'- `app/src/main/assets/searchhh-codebases.json` is shipped reference data, **not executable runtime configuration**.',
'- `tools/crawlers/render_audit.py` generates this report without network access.',
'- [Implementation plan](PLAN-0.4-CRAWLERS.md); [source/license ledger](SOURCES.md); [release validation](RELEASE-0.4.md).', '']
(ROOT / 'docs/searchhh/CRAWLER-AUDIT.md').write_text('\n'.join(lines))
