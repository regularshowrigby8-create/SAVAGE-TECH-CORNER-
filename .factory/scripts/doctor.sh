#!/bin/bash
# doctor.sh — Finds placeholders, missing labels, missing remotes, setup gaps.
# HONEST GATE: any violation exits 1. A "check" that always exits 0 is not a
# check — it is decoration.
set -euo pipefail

echo "DOCTOR CHECK START"
FAILURES=0
fail() { echo "DOCTOR-FAIL: $1"; FAILURES=$((FAILURES + 1)); }

# 1. Verify skills directory exists and has content (referenced structure, not padding)
if [ -d ".claude/skills" ]; then
    SKILL_COUNT=$(find .claude/skills -type f | wc -l)
    if [ "$SKILL_COUNT" -eq 0 ]; then
        fail ".claude/skills/ exists but is empty"
    else
        echo "Skills directory verified: $SKILL_COUNT skill files"
    fi
else
    fail ".claude/skills/ missing"
fi

# 2. Authentic Placeholder and Incomplete Code Scanner
echo "Scanning for unresolved placeholders and stubs..."
PLACEHOLDER_VIOLATIONS=0
SCAN_DIRS=(shell frontend backend factory-2 factories tools scripts)
for dir in "${SCAN_DIRS[@]}"; do
    if [ ! -d "$dir" ]; then
        fail "scan directory '$dir' missing — placeholder scan would silently skip it"
    fi
done

# Actionable placeholder = comment-anchored token followed by a separator
# (real markers look like "# TODO: ..."). Detector definitions, report
# titles, and regex literals mentioning the words are NOT placeholders.
while IFS= read -r file; do
    if grep -nEi "#\s*(TODO|FIXME|XXX|INCOMPLETE|STUB)\s*[:—-]" "$file" >/dev/null 2>&1; then
        echo "Placeholder finding in: $file"
        grep -nEi "#\s*(TODO|FIXME|XXX|INCOMPLETE|STUB)\s*[:—-]" "$file" | head -3
        PLACEHOLDER_VIOLATIONS=$((PLACEHOLDER_VIOLATIONS + 1))
    fi
done < <(find "${SCAN_DIRS[@]}" -name "*.py" -not -path "*/tests/*" 2>/dev/null)

if [ "$PLACEHOLDER_VIOLATIONS" -eq 0 ]; then
    echo "Placeholder scan clean: 0 active placeholders found."
else
    fail "Found $PLACEHOLDER_VIOLATIONS file(s) with potential unresolved placeholders."
fi

# 3. Environment & Remote configuration check
if [ -z "${GITHUB_ACTIONS:-}" ]; then
    echo "Running locally (not in CI)"
fi
if git remote -v | grep -q origin; then
    echo "Remote configured: $(git remote get-url origin 2>/dev/null || echo 'unknown')"
else
    fail "No git remote configured"
fi

if [ "$FAILURES" -gt 0 ]; then
    echo "DOCTOR CHECK COMPLETE: FAIL ($FAILURES problem(s))"
    exit 1
fi
echo "DOCTOR CHECK COMPLETE: PASS"
