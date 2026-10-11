"""Explicit maintainer audit via authenticated gh. Never downloads or runs repo code.
Produces metadata, NOT an executable integration allowlist. Run from any cwd.
"""
import concurrent.futures
import json
from pathlib import Path
import subprocess
from urllib.parse import urlsplit
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
INPUT = Path(__file__).with_name('codebases.tsv')
OUTPUT = ROOT / 'app/src/main/assets/searchhh-codebases.json'
OVERRIDES = json.loads(Path(__file__).with_name('overrides.json').read_text())


def api(path):
    p = subprocess.run(['gh', 'api', path], capture_output=True, text=True, timeout=40)
    if p.returncode:
        # No environment, credentials, headers or complete error response persisted.
        code = next((n for n in ('404','403','429','401') if f'HTTP {n}' in p.stderr), 'transport_error')
        return None, code
    return json.loads(p.stdout), None


def inspect(item):
    number, line = item
    name, language, submitted, category = line.split('|')
    row = dict(number=number, original=number <= 100, name=name,
               submittedUrl=submitted, submittedLanguage=language, category=category,
               canonicalUrl=None, repositoryStatus='unresolved', license='unverified',
               description='', revision=None, archived=None, integration='catalog_only',
               executionTarget='server_or_desktop',
               configurationSupport={key:'unverified' for key in
                   ('robotsTxt','rateLimits','politenessDelays','maxDepth','domainScope')})
    override = OVERRIDES.get(str(number), {})
    lookup = override.get('lookupUrl', submitted)
    u = urlsplit(lookup)
    if any(c.isspace() for c in lookup):
        row['repositoryStatus']='malformed_url'
    elif u.hostname != 'github.com':
        row['repositoryStatus']='external_review_required'
    else:
        slug = u.path.strip('/')
        try:
            meta, err = api('repos/'+slug)
            if err:
                row['repositoryStatus']='not_found' if err=='404' else 'lookup_failed_'+err
            else:
                row.update(canonicalUrl=meta['html_url'], repositoryStatus='github_metadata_verified',
                           license=(meta.get('license') or {}).get('spdx_id') or 'unverified',
                           description=(meta.get('description') or '')[:700],
                           reportedLanguage=meta.get('language'), archived=meta['archived'],
                           pushedAt=meta['pushed_at'], defaultBranch=meta['default_branch'],
                           evidenceUrl='https://api.github.com/repos/'+meta['full_name'])
                commit, err = api('repos/'+meta['full_name']+'/commits/'+meta['default_branch'])
                if not err:
                    row['revision']=commit['sha']
                    row['sourceUrl']=meta['html_url']+'/tree/'+commit['sha']
                row['licenseEvidenceUrl']='https://api.github.com/repos/'+meta['full_name']+'/license'
        except (subprocess.TimeoutExpired, ValueError, KeyError):
            row['repositoryStatus']='lookup_failed'
    row.update({k:v for k,v in override.items() if k != 'lookupUrl'})
    if lookup != submitted:
        row['correctedLookupUrl'] = lookup
    if row['canonicalUrl'] and row['canonicalUrl'] != submitted and 'note' not in row:
        row['note'] = 'GitHub resolved a renamed/transferred repository; original URL retained.'
    if number == 1:
        row['integration']='existing_optional_backend'
        row['executionTarget']='python_optional_backend_not_android'
    if category in ('indexer','parser','http_client','downloader','structured_extractor','extractor'):
        row['configurationSupport']={k:'not_a_crawler_policy_interface' for k in row['configurationSupport']}
    return row


def main():
    lines = INPUT.read_text().splitlines()
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        rows = list(pool.map(inspect, enumerate(lines, 1)))
    data = dict(schemaVersion=1, auditedAt=datetime.now(timezone.utc).isoformat(),
                evidenceScope='GitHub repository metadata/source revision; external origins and per-tool configuration need separate evidence. Not compilation or runtime verification.',
                requestedIgnoreProfile=dict(robotsTxt=True, rateLimits=True, politenessDelays=True, maxDepth=True, domainScope=True),
                requestedProfileIsRuntimeConfig=False, entries=rows)
    OUTPUT.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n')
    for r in rows:
        print(r['number'], r['name'], r['repositoryStatus'], r['canonicalUrl'], r['license'], 'archived='+str(r['archived']))


if __name__ == '__main__':
    main()
