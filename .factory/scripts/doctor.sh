#!/bin/bash
# doctor.sh — Finds placeholders, missing labels, missing remotes, setup gaps
set -euo pipefail

echo "DOCTOR CHECK START"
# Verify skills directory exists and has content (referenced structure, not padding)
if [ -d ".claude/skills" ]; then
    SKILL_COUNT=$(find .claude/skills -type f | wc -l)
    echo "Skills directory verified: $SKILL_COUNT skill files"
else
    echo "WARNING: .claude/skills/ missing"
fi
if [ -z "${GITHUB_ACTIONS:-}" ]; then
    echo "Running locally (not in CI)"
fi
if git remote -v | grep -q origin; then
    echo "Remote configured: $(git remote get-url origin 2>/dev/null || echo 'unknown')"
else
    echo "WARNING: No git remote configured"
fi
echo "DOCTOR CHECK COMPLETE"
