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
#   4. store the licence texts that are missing and index the store, so the seal can pin the index;
#   5. re-seal, verify the seal it just wrote, and check the licence store against it;
#   6. let the seal recheck itself with the factories it carries, repairing what can be repaired, and
#      refuse to go on unless it comes out functional;
#   7. run the sealed runtime in plan mode - no model, no keys - and judge the set against the ledger
#      the committed set has already reached;
#   8. publish the counts the run produced, for the commit step and the job summary.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

REPORT="sealed-factories/manifest/RUN-REPORT.json"
SEAL="sealed-factories/SEAL.json"
REGISTRY="tools/factory/imported_factories.json"

step() { echo; echo "=== $* ==="; }

# Run a command and, when it fails, annotate its own output. A gate that fails into a log nobody can
# fetch is a gate nobody can act on: that is why the belt's failure reporter was rewritten, and this is
# the same rule applied to every tool this script runs.
run_and_report() {
    local title="$1"
    shift
    local log
    log="$(mktemp)"
    local status
    if "$@" > "$log" 2>&1; then
        cat "$log"
        rm -f "$log"
        return 0
    else
        # Read here, not after the `fi`: an if statement with no else branch is itself status 0.
        status=$?
    fi
    cat "$log"
    echo "::error title=Factory growth::${title} failed (exit ${status}); its own output follows"
    if grep -a -q -E 'FAIL|ERROR|Error|error|missing|refused|cannot|Cannot|Traceback' "$log"; then
        grep -a -A 3 -E 'FAIL|ERROR|Error|error|missing|refused|cannot|Cannot|Traceback' "$log" \
            | tail -25 | while read -r line; do
                echo "::error title=Factory growth::- ${line}"
            done
    else
        tail -25 "$log" | while read -r line; do
            echo "::error title=Factory growth::- ${line}"
        done
    fi
    rm -f "$log"
    return "$status"
}

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

# The component's own self-check runs here too, in repair mode, between the vendoring and the seal: a
# re-vendor that damaged a slice or a licence text while fetching is repaired now, in the middle of the
# run, instead of being reported as a failure at the end of it. The strict pass (--require-functional)
# comes after the seal; this one is the component fixing what it can as it goes.
step "the seal repairing itself after the re-vendor"
run_and_report "the mid-run self-check" python3 sealed-factories/runtime/cli.py selfcheck --repair
MIDCHECK_STATUS=$?
if [ "$MIDCHECK_STATUS" -ne 0 ] && [ "$MIDCHECK_STATUS" -ne 1 ]; then
    # 0 fit, 1 not fit but repaired-what-it-could; anything else (3: no seal) stops the run here.
    echo "::error title=Factory growth::the self-check could not run (exit ${MIDCHECK_STATUS})"
    exit 1
fi
if [ "$MIDCHECK_STATUS" -eq 1 ]; then
    # Not hidden and not tolerated as a pass: the findings were just annotated by the tool itself, and
    # the steps that follow - store the licences, index the store, seal, and the strict self-check with
    # --require-functional - are what has to clear them. If they do not, this run fails there.
    echo "::warning title=Factory growth::the mid-run self-check still has blocking findings; the licence store, the seal and the strict self-check that follow have to clear them"
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

# 4. The licence store: every text the set is missing is stored - from the vendored record when it
#    already carries the bytes, otherwise from the repository's own published file - and the store is
#    indexed. The index is pinned by the seal, so it is written here, before the seal, not after it.
step "licence store: storing the texts the set is missing, then indexing what is stored"
python3 tools/factory/license_manager.py import
IMPORT_STATUS=$?
if [ "$IMPORT_STATUS" -ne 0 ]; then
    echo "::error title=Factory growth::the licence import could not complete (exit ${IMPORT_STATUS}); a store missing a text is not a stored set"
    exit 1
fi
# The index is written before the seal on purpose: the seal pins it, so it has to be current when the
# seal is written rather than afterwards.
python3 tools/factory/license_manager.py index || exit 1

# 5. The seal carries content only where the sealer's own policy allows it, so it is re-sealed whenever
#    a slice changed, a record was added, or nothing changed at all - a seal is cheap and the verify
#    below is what proves the result.
step "sealing the grown set"
python3 tools/factory/seal_factories.py seal || exit 1
python3 tools/factory/sealed_factory_runtime.py verify || exit 1
# Now that the seal describes the set, the store can be checked against it: a text the seal names and
# the store lost, a text no record names, a record carrying content with no licence beside it.
python3 tools/factory/license_manager.py verify || exit 1

# 6. The component rechecks itself with the factories it carries, and repairs what it can: the reading
#    of every slice comes from the same reader a run uses, each repair is handed to the functional
#    record whose own definitions name that capability, and the repairs run the component's own tools.
#    The set has to come out functional with only named states left (the licence wall; slices that
#    define nothing to run). A blocking finding that survives the repair fails the run here rather than
#    being committed.
step "the seal rechecking itself with the factories it carries"
run_and_report "the self-check" python3 sealed-factories/runtime/cli.py selfcheck --repair --require-functional
SELFCHECK_STATUS=$?
SELF_CHECK_REPORT="sealed-factories/manifest/self-check.json"
# The run's verdict belongs somewhere a reader can always reach it: annotations are capped, and a hosted
# runner's raw log is not always retrievable. The step summary carries the verdict, every blocking
# finding with the factory that owns it, the named states and what each repair did.
if [ -s "$SELF_CHECK_REPORT" ] && [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo "## Sealed set self-check"
        echo
        python3 - "$SELF_CHECK_REPORT" <<'SUMMARY_PY'

import json
import sys

report = json.load(open(sys.argv[1]))
print(f"Verdict **{report['verdict']}**: {report['records']} records, {report['carried']} carrying "
      f"content, {report['licence_texts']} licence texts in the seal, {report['store_texts']} in "
      f"the store, reader {'used' if report['reader_used'] else 'ABSENT'}.")
print()
if report["blocking"]:
    print("Blocking findings:")
    print()
    for finding in report["blocking"]:
        owner = finding.get("assigned_to") or "unassigned"
        print(f"- `{finding['code']}` {finding['record']} ({owner}) - {finding['detail']}")
    print()
states = {}
for state in report["states"]:
    states[state["code"]] = states.get(state["code"], 0) + 1
print(f"Named states: {states or 'none'} (a state is not a defect and is not counted as fitness).")
if report["repairs"]:
    print()
    print("Repairs:")
    print()
    for repair in report["repairs"]:
        state = ("repaired" if repair.get("repaired")
                 else ("refused" if repair.get("refused") else "not repaired"))
        print(f"- {state}: {repair.get('record')} - {repair.get('detail')}")
SUMMARY_PY
    } >> "$GITHUB_STEP_SUMMARY"
fi
if [ "$SELFCHECK_STATUS" -ne 0 ]; then
    echo "::error title=Factory growth::the sealed set could not prove itself functional (exit ${SELFCHECK_STATUS})"
    if [ -s "$SELF_CHECK_REPORT" ]; then
        # Annotated once more here, first eight, because the summary is prose and the run page's checks
        # list is what a reader sees first. A refusal always names itself in one of the two.
        python3 - "$SELF_CHECK_REPORT" <<'ANNOTATE_PY'

import json
import sys

report = json.load(open(sys.argv[1]))
for finding in report["blocking"][:8]:
    print(f"::error title=Sealed self-check::{finding['code']} {finding['record']}: "
          f"{finding['detail']}")
ANNOTATE_PY
    fi
    exit 1
fi

# 7. The run: plan mode, no model calls, and the environment it reads is the seal written above.
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

# 8. Publish what the run changed, for the commit step and for whoever reads the job afterwards. The
#    self-check's verdict is read from its own report and re-asserted here: the counts below are only
#    worth anything next to the verdict of the run that produced them, and a missing report is a
#    failure rather than an unknown.
if [ ! -s sealed-factories/manifest/self-check.json ]; then
    echo "::error title=Factory growth::the self-check wrote no report"
    exit 1
fi
SELFCHECK_VERDICT="$(python3 -c "import json;print(json.load(open('sealed-factories/manifest/self-check.json'))['verdict'])")"
if [ "$SELFCHECK_VERDICT" != "FUNCTIONAL" ]; then
    echo "::error title=Factory growth::the self-check verdict is ${SELFCHECK_VERDICT}"
    exit 1
fi
if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
        echo "vendored=${VENDORED}"
        echo "records=$(python3 -c "import json;print(json.load(open('$SEAL'))['contents']['factories'])")"
        echo "operational=$(python3 -c "import json;print(json.load(open('$REPORT'))['summary']['operational_plan'])")"
        echo "licence_texts=$(python3 -c "import json;print(json.load(open('$SEAL'))['contents']['with_license_text'])")"
        echo "selfcheck=${SELFCHECK_VERDICT}"
    } >> "$GITHUB_OUTPUT"
fi
echo "factory growth: PASS"
