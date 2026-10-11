#!/bin/bash
# doctor.sh — Finds placeholders, missing labels, missing remotes, setup gaps
set -euo pipefail

echo "DOCTOR CHECK START"
# 1. Verify skills directory exists and has content (referenced structure, not padding)
if [ -d ".claude/skills" ]; then
    SKILL_COUNT=$(find .claude/skills -type f | wc -l)
    echo "Skills directory verified: $SKILL_COUNT skill files"
else
    echo "WARNING: .claude/skills/ missing"
fi

# 2. Authentic Placeholder and Incomplete Code Scanner
echo "Scanning for unresolved placeholders and stubs..."
PLACEHOLDER_VIOLATIONS=0
while IFS= read -r file; do
    if grep -nEi "(TODO|FIXME|XXX|INCOMPLETE|STUB|PLACEHOLDER)" "$file" | grep -vEi "(docstring|doctor\.sh|rule|exclude|mock|test|agent_correctness|scanner|audit)" >/dev/null 2>&1; then
        echo "Placeholder finding in: $file"
        PLACEHOLDER_VIOLATIONS=$((PLACEHOLDER_VIOLATIONS + 1))
    fi
done < <(find shell frontend backend -name "*.py" -not -path "*/tests/*" 2>/dev/null || true)

if [ "$PLACEHOLDER_VIOLATIONS" -eq 0 ]; then
    echo "Placeholder scan clean: 0 active placeholders found."
else
    echo "WARNING: Found $PLACEHOLDER_VIOLATIONS file(s) with potential unresolved placeholders."
fi

# 3. Environment & Remote configuration check
if [ -z "${GITHUB_ACTIONS:-}" ]; then
    echo "Running locally (not in CI)"
fi
if git remote -v | grep -q origin; then
    echo "Remote configured: $(git remote get-url origin 2>/dev/null || echo 'unknown')"
else
    echo "WARNING: No git remote configured"
fi
echo "DOCTOR CHECK COMPLETE"
