#!/usr/bin/env bash
# Grow the sealed factory set on GitHub, where the network and the tools are, and judge what came back.
#
# The local sandbox this work started in can neither run a long job nor reach GitHub's release assets,
# so growth happens here instead: on a hosted runner, against the live GitHub API, committing the result
# back to the branch. Each step is the same tool the repository already uses by hand, and every step
# fails the job rather than reporting a partial result as a whole one.
#
#   1. re-read every catalogued repository's own licence text (widening only where the registry
#      recorded no licence) and re-apply the registry contract to the catalog document;
#   2. import further factories from GitHub search and account for every repository a plan named;
#   3. re-vendor only the records whose licence or slice changed, then rebuild every payload;
#   4. re-seal, and verify the seal it just wrote;
#   5. run the sealed runtime in plan mode - no model, no keys - and judge the set against the ledger
#      the committed set has already reached;
#   6. publish the counts the run produced, for the commit step and the job summary.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

REPORT="sealed-factories/manifest/RUN-REPORT.json"
SEAL="sealed-factories/SEAL.json"
REGISTRY="tools/factory/imported_factories.json"

step() { echo; echo "=== $* ==="; }

# 1. The licence wall, re-derived from each repository's own text. `--fail-on-unreadable` matters on a
#    runner: a rate-limited scan leaves records untouched, which is safe but incomplete, and this is
#    what makes that visible instead of reporting it as a clean scan.
step "licence re-scan"
python3 tools/factory/license_rescan.py --apply --fail-on-unreadable
RESCAN_STATUS=$?
if [ "$RESCAN_STATUS" -ne 0 ]; then
    echo "::error title=Factory growth::the licence re-scan failed (exit ${RESCAN_STATUS}); nothing below would be trustworthy"
    exit 1
fi

# 2. Growth, in two passes that share one registry: a search for repositories new to the set, then the
#    repositories a plan named. Both refuse anything whose own tree is not factory-shaped, and both
#    rewrite the catalog document so it keeps agreeing with the registry.
step "importing factories found by search"
python3 tools/factory/discover_factories.py --apply --limit 6 \
    --query '"agent skills" in:name,description' \
    --query '"software factory" in:name,description' \
    --query '"agent workflows" in:name,description'
SEARCH_STATUS=$?
if [ "$SEARCH_STATUS" -ne 0 ]; then
    echo "::error title=Factory growth::the search import failed (exit ${SEARCH_STATUS})"
    exit 1
fi

step "accounting for every repository the plan named"
python3 tools/factory/discover_factories.py --from-plan tools/factory/repositories.json --apply --limit 9
PLAN_STATUS=$?
if [ "$PLAN_STATUS" -ne 0 ]; then
    echo "::error title=Factory growth::the plan import failed (exit ${PLAN_STATUS})"
    exit 1
fi

# 3. Re-vendor only what changed. The list comes from the records themselves, so a run with nothing to
#    do makes no fetch at all.
step "vendoring the slices that changed"
PENDING="$(python3 tools/factory/vendor_needed.py)"
NEEDED_STATUS=$?
if [ "$NEEDED_STATUS" -ne 0 ]; then
    echo "::error title=Factory growth::the vendoring plan could not be computed (exit ${NEEDED_STATUS})"
    exit 1
fi
VENDORED=0
if [ -n "$PENDING" ]; then
    while IFS= read -r line; do
        kind="${line%%:*}"
        ids="${line#*:}"
        echo "--- ${kind}: ${ids}"
        case "$kind" in
            imported) python3 tools/factory/vendor_imported_factories.py --refresh --only "$ids" || exit 1 ;;
            ai|mcp)   python3 tools/factory/vendor_mcp_servers.py --kind "$kind" --refresh --only "$ids" || exit 1 ;;
            *) echo "::error title=Factory growth::unknown registry kind '${kind}'"; exit 1 ;;
        esac
        VENDORED=1
    done <<< "$PENDING"
else
    echo "no catalogued factory needs vendoring: every slice matches the licence its record now holds"
fi

# A record with no slice is absent from the seal without anything failing - the sealer numbers what it
# finds, and the set would simply be one smaller. Asking the same question again, after vendoring, is
# what turns that silence into a failure: every record must now have a slice.
STILL="$(python3 tools/factory/vendor_needed.py)"
STILL_STATUS=$?
if [ "$STILL_STATUS" -ne 0 ]; then
    echo "::error title=Factory growth::the vendoring check could not be computed (exit ${STILL_STATUS})"
    exit 1
fi
if [ -n "$STILL" ]; then
    echo "::error title=Factory growth::these records have no slice to seal, so they would be silently absent: ${STILL}"
    exit 1
fi

# 4. The seal carries content only where the sealer's own policy allows it, so it is re-sealed whenever
#    a slice changed, a record was added, or nothing changed at all - a seal is cheap and the verify
#    below is what proves the result.
step "sealing the grown set"
python3 tools/factory/seal_factories.py seal || exit 1
python3 tools/factory/sealed_factory_runtime.py verify || exit 1

# 5. The run: plan mode, no model calls, and the environment it reads is the seal written above.
step "sealed run (plan mode, no model)"
rm -f "$REPORT"
python3 tools/factory/sealed_factory_runtime.py run --max-model-calls 0
RUN_STATUS=$?
if [ "$RUN_STATUS" -ne 0 ]; then
    echo "::error title=Factory growth::the sealed run exited ${RUN_STATUS}"
    exit 1
fi
if [ ! -s "$REPORT" ]; then
    echo "::error title=Factory growth::the run wrote no report at ${REPORT}"
    exit 1
fi

step "judging the grown set"
python3 tools/ci/factory_growth_judge.py "$REPORT" "$SEAL" "$REGISTRY"
JUDGE_STATUS=$?
if [ "$JUDGE_STATUS" -ne 0 ]; then
    echo "::error title=Factory growth::the set grew but the judge refused it; see FACTORY GROWTH FAIL above"
    exit 1
fi

# 6. Publish what the run changed, for the commit step and for whoever reads the job afterwards.
if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
        echo "vendored=${VENDORED}"
        echo "records=$(python3 -c "import json;print(json.load(open('$SEAL'))['contents']['factories'])")"
        echo "operational=$(python3 -c "import json;print(json.load(open('$REPORT'))['summary']['operational_plan'])")"
    } >> "$GITHUB_OUTPUT"
fi
echo "factory growth: PASS"
