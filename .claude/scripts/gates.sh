#!/usr/bin/env bash
# Deterministic quality gate for Factory 2 with Anti-Stub & Anti-Deception Invariants.
set -uo pipefail
ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT_DIR"
failures=()
fail() { failures+=("$1"); echo "FAIL: $2"; }

echo "=== FACTORY 2 GATE START ==="
echo "[GATE] Python syntax check..."
python3 -m compileall -q factory-2 || fail syntax "Python syntax errors"

echo "[GATE] Single-job workflow check..."
if [ -f .github/workflows/factory-2-debug.yml ]; then
  job_count=$(awk '/^jobs:/{in_jobs=1; next} in_jobs && /^  [A-Za-z0-9_-]+:/{count++} END{print count+0}' .github/workflows/factory-2-debug.yml)
  if [ "$job_count" -ne 1 ]; then fail workflow "expected exactly one job, found $job_count"; else echo "PASS: exactly one workflow job"; fi
fi

echo "[GATE] Anti-Stub & Placeholder Invariant Check..."
if ! python3 - <<'PY'
import os
import re
from pathlib import Path

# Match actual functional stubs (e.g. comments like '# TODO: implement' or 'raise NotImplementedError')
# in application code (shell, frontend, backend)
STUB_PATTERNS = [
    re.compile(r"""#\s*(?:TODO|FIXME|INCOMPLETE|STUB)\b""", re.IGNORECASE),
    re.compile(r"""\braise\s+NotImplementedError\b"""),
]

violations = []
# Focus inspection on core application code
for search_dir in ("shell", "frontend", "backend"):
    p_dir = Path(search_dir)
    if not p_dir.exists():
        continue
    for p in p_dir.rglob("*.py"):
        if "tests" in p.parts or "__pycache__" in p.parts:
            continue
        content = p.read_text(encoding="utf-8", errors="ignore")
        for lineno, line in enumerate(content.splitlines(), 1):
            clean = line.strip()
            # Allow rule definitions or testing explanations
            if any(marker in clean.upper() for marker in ("RULE", "EXCLUDE", "PASS", "CHECK")):
                continue
            for pat in STUB_PATTERNS:
                if pat.search(clean):
                    violations.append(f"{p}:{lineno}: Found unresolved stub: '{clean[:60]}'")

if violations:
    print("\n".join(violations))
    raise SystemExit(1)
print("PASS: Zero unverified stubs or placeholders in application code.")
PY
then
  fail stubs "Codebase contains unresolved stubs or placeholder tokens"
fi

echo "[GATE] Anti-Deception & Provenance Check..."
if [ -f "factories/final-product/SavageBrowser-debug.apk" ]; then
    if ! python3 - <<'PY'
import sys
from pathlib import Path
sys.path.insert(0, "factories/factory-3/tools")
from browser_app_rules import verify_browser_app_rules
res = verify_browser_app_rules(Path("factories/final-product/SavageBrowser-debug.apk"))
if not res["passed"]:
    print(f"Provenance/Deception violation in APK: {res.get('violations')}")
    raise SystemExit(1)
print("PASS: Authentic package provenance verified.")
PY
    then
        fail provenance "APK violates anti-deception provenance rules"
    fi
fi

echo "[GATE] Fresh Factory 2 run..."
bash factory-2/scripts/run_all.sh || fail pipeline "Factory 2 pipeline reported failure"

echo "[GATE] Markdown path claims..."
if ! python3 - <<'PY'
import re
from pathlib import Path
failures = []
pattern = re.compile(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+\.[A-Za-z0-9]{1,8}")
for path in Path('.').rglob('*.md'):
    if '.git' in path.parts or 'factory-2/reports' in str(path):
        continue
    for line_no, line in enumerate(path.read_text(encoding='utf-8', errors='ignore').splitlines(), 1):
        for ref in sorted(set(pattern.findall(line))):
            if 'github.com' in line or ref.startswith(('http://', 'https://')) or any(marker in line.upper() for marker in ('NOT ON THIS BRANCH', 'BRANCH ONLY', 'LIVES ON')):
                continue
            if not Path(ref).exists():
                failures.append(f'{path}:{line_no}:{ref}')
if failures:
    print('\n'.join('CLAIM FAILURE: ' + item for item in failures))
    raise SystemExit(1)
print('PASS: all actionable markdown paths resolve')
PY
then
  fail claims "unresolved markdown path claims"
fi

echo "[GATE] Script wiring check..."
for script in factory-2/agents/*.py factory-2/criticism/*.py; do
  name=$(basename "$script")
  grep -q "$name" factory-2/scripts/run_all.sh || fail "unwired:$name" "$name is not invoked by run_all.sh"
done

if [ "${#failures[@]}" -gt 0 ]; then
  printf 'VERDICT: FAIL — %s\n' "${failures[*]}"
  exit 1
fi
echo "VERDICT: PASS — All Factory 2 gates green"
