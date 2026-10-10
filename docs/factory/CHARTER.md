# SAVAGE TECH CORNER — GLOBAL CHARTER

This charter applies to EVERYTHING in this repository, across ALL branches.

## Non-Negotiable Rules (Every Branch, Every PR)

1. **No dead code** — Any unreachable, unused, or orphaned function must be flagged and removed.
2. **No incomplete code** — Partial implementations must include a `// TODO: INCOMPLETE` comment and a Linear issue.
3. **No dead buttons** — Every interactive element must have a verified handler.
4. **Every PR must pass the Factory Gate** — `gates.sh` runs before merge.
5. **One job per factory** — Factory 2 runs exactly ONE GitHub Actions job at a time.
6. **Feedback is mandatory** — All agent feedback must be documented, accepted or rejected in writing.

## Branch Rules

| Branch | Purpose | Rules |
|---|---|---|
| `main` | Rules, contracts, gates | Human-owned tier; load-bearing paths only |
| `arena/ac306e97-savage-tech-corner` | Development work | All new features, docs, integrations |
| `factory-1` | My code space | Personal workspace; no CI blocks except gates |
| `factory-2` | Debug environment | AI agents only; one job; criticism checks |

## Human-Owned Tier (Load-Bearing)

- `docs/factory/CHARTER.md` — This file
- `docs/factory/CONTRACT.md` — Queue protocol
- `.github/workflows/factory-gate.yml` — The gate
- `.claude/scripts/gates.sh` — Deterministic checks

## Automatable Work

- Issue triage, spec generation, test writing, code review, reflection.
- Nothing merges without CI + independent verification.
