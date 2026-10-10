#!/usr/bin/env bash
# Deterministic quality gate for Factory 1.
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT_DIR"

failures=()
fail() { failures+=("$1"); echo "FAIL: $2"; }

echo "=== FACTORY 1 GATE START ==="
echo "[GATE] Python syntax check..."
python3 -m compileall -q workspace/code || fail syntax "Python syntax errors"
echo "[GATE] Behavioral tests..."
python3 -m unittest discover -s workspace/code -p 'test*.py' || fail tests "Factory 1 tests failed"
echo "[GATE] Source scanners..."
if ! python3 - <<'PY'
from workspace.code.debug_pipeline import run_debug_pipeline
result = run_debug_pipeline("workspace/code")
print(result)
raise SystemExit(0 if result["passed"] else 1)
PY
then
  fail scanners "Factory 1 source scanners reported findings"
fi

echo "[GATE] Workflow job count..."
if [ -f .github/workflows/factory-2-debug.yml ]; then
  job_count=$(awk '/^jobs:/{in_jobs=1; next} in_jobs && /^  [A-Za-z0-9_-]+:/{count++} END{print count+0}' .github/workflows/factory-2-debug.yml)
  if [ "$job_count" -ne 1 ]; then fail workflow "expected exactly one workflow job, found $job_count"; else echo "PASS: exactly one workflow job"; fi
fi

if [ "${#failures[@]}" -gt 0 ]; then
  printf 'VERDICT: FAIL — %s\n' "${failures[*]}"
  exit 1
fi
echo "VERDICT: PASS — All Factory 1 gates green"
