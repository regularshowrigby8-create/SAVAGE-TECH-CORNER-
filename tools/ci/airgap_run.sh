#!/usr/bin/env bash
# The air-gapped sealed run: prove the offline path by running it with networking switched off.
#
# `SEALED_NO_NETWORK=1` is the component's own switch (see docs/ACCEPTANCE_CRITERIA.md, AC-051 and
# AC-060). Tests assert the switch's behaviour against a stub server; this script is the other half -
# a real run of the whole sealed environment with the switch on, in CI, on a job that installs no npm
# dependencies at all, so nothing can quietly depend on a registry being reachable.
#
# What it gates on, all read from what the run itself produced:
#   1. the seal verifies;
#   2. the run exits 0;
#   3. the run's own report says every check passed and no step failed;
#   4. no model call was made or claimed;
#   5. the researcher role's artefact records the switch as the reason its GitHub probe is
#      unavailable - the evidence that the air-gapped path was the one exercised.
# A run that skips the probe, or that probes and finds the network anyway, fails here.
set -uo pipefail

LOG="${RUNNER_TEMP:-/tmp}/airgap.log"
WORKSPACE="${GITHUB_WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
cd "$WORKSPACE"

export SEALED_NO_NETWORK=1
REPORT="sealed-factories/manifest/RUN-REPORT.json"
SWARM_ROOT="sealed-factories/manifest/workspace"

echo "=== air-gapped run: SEALED_NO_NETWORK=${SEALED_NO_NETWORK} ===" | tee -a "$LOG"

python3 tools/factory/sealed_factory_runtime.py verify 2>&1 | tee -a "$LOG"
VERIFY_STATUS=${PIPESTATUS[0]}
if [ "$VERIFY_STATUS" -ne 0 ]; then
    echo "::error title=air-gapped run::the seal did not verify with networking switched off (exit ${VERIFY_STATUS})"
    exit 1
fi

# Clear what the previous run wrote, so the artefact the judge reads was produced by *this* run: a
# stale researcher artefact carrying the switch could otherwise satisfy the gate on its own.
rm -f "$REPORT"
rm -rf "$SWARM_ROOT/.sealed/swarm"
python3 tools/factory/sealed_factory_runtime.py run --max-model-calls 0 --env-limit 200 2>&1 | tee -a "$LOG"
RUN_STATUS=${PIPESTATUS[0]}
if [ "$RUN_STATUS" -ne 0 ]; then
    echo "::error title=air-gapped run::the sealed environment exited ${RUN_STATUS} with networking switched off"
    exit 1
fi

if [ ! -s "$REPORT" ]; then
    echo "::error title=air-gapped run::the run wrote no report at ${REPORT}, so nothing below can be checked"
    exit 1
fi

python3 tools/ci/airgap_judge.py "$REPORT" "$SWARM_ROOT"
JUDGE_STATUS=$?
if [ "$JUDGE_STATUS" -ne 0 ]; then
    echo "::error title=air-gapped run::the run completed but did not prove the air-gapped path; see AIR-GAP FAIL above"
    exit 1
fi

echo "air-gapped sealed run: PASS" | tee -a "$LOG"
