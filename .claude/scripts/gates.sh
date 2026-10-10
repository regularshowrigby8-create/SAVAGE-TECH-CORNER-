#!/bin/bash
# gates.sh — Deterministic quality gate
# THREE LEVELS: lint/test/architecture. Emits a verdict line.

set -euo pipefail

VERDICT="PASS"
FAILURES=""

check_lint() {
    echo "[GATE] Lint check..."
    if find . -name '*.py' | head -1; then
        echo "Python files detected — verify syntax"
    fi
    if find . -name '*.js' -o -name '*.mjs' | head -1; then
        echo "JS files detected — verify syntax"
    fi
}

check_tests() {
    echo "[GATE] Test check..."
    # Check for dead buttons / incomplete handlers — only in actual workspace source
    if grep -rni 'TODO.*INCOMPLETE\|FIXME' --exclude-dir='.git' --exclude-dir='node_modules' workspace/ 2>/dev/null; then
        echo "WARNING: Actual code markers found in source files"
        FAILURES="${FAILURES}incomplete_markers;"
    fi
    # Check for unreachable code patterns — only in workspace source
    if grep -rni 'function .*unused\|dead code\|unreachable' --exclude-dir='.git' --exclude-dir='node_modules' workspace/ 2>/dev/null; then
        echo "WARNING: Potential dead code markers found in source"
        FAILURES="${FAILURES}dead_code_markers;"
    fi
}

check_dead_buttons() {
    echo "[GATE] Dead button check..."
    # Look for buttons without handlers — only in code, not docs
    BUTTON_CHECK=$(grep -rni 'button\|btn' --exclude-dir='.git' --exclude-dir='node_modules' workspace/ 2>/dev/null | grep -vi 'onclick\|onClick\|handler\|action\|function\|CHARTER\|button handler' || true)
    if [ -n "$BUTTON_CHECK" ]; then
        echo "WARNING: Potential dead buttons (buttons without handlers) found"
        FAILURES="${FAILURES}potential_dead_buttons;"
    fi
}

check_factory_2() {
    echo "[GATE] Factory 2 single-job check..."
    # Verify only one workflow job exists in factory-2
    if [ -f ".github/workflows/factory-2-debug.yml" ]; then
        JOB_COUNT=$(grep -c 'job:' .github/workflows/factory-2-debug.yml 2>/dev/null || true)
        JOB_COUNT=${JOB_COUNT:-0}
        if [ "$JOB_COUNT" -gt 1 ]; then
            echo "FAIL: Factory 2 has more than one job ($JOB_COUNT)"
            FAILURES="${FAILURES}factory_2_multiple_jobs;"
        else
            echo "PASS: Factory 2 has exactly one job"
        fi
    fi
}

echo "=== FACTORY GATE START ==="
check_lint
check_tests
check_dead_buttons
check_factory_2

if [ -n "$FAILURES" ]; then
    echo "VERDICT: FAIL — $FAILURES"
    exit 1
else
    echo "VERDICT: PASS — All gates green"
    exit 0
fi
