# NO PADDING — REPO-WIDE RULE

Every file must have a real purpose and be reachable from:
- `.claude/scripts/gates.sh` (gate checks)
- `.github/workflows/factory-2-debug.yml` (CI job)
- `factory-2/scripts/run_all.sh` (pipeline execution)
- Or a direct reference from `docs/factory/CHARTER.md` or `docs/factory/CONTRACT.md`

No identical copies with only a number changed.
No directories that exist but are never executed or referenced.
Violation = removal, not justification.
