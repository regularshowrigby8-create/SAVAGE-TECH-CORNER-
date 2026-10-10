# FACTORY CONTRACT — SAVAGE TECH CORNER

## Queue Protocol

Every work item flows through these stages:

1. **Triage** (`.claude/skills/factory-triage/`) — Issues get labels: `factory:triage`, `factory:ready`, `factory:blocked`, `factory:done`.
2. **Spec** (`.claude/skills/factory-spec/`) — Four gates before any code exists.
3. **Implement** (`.claude/skills/factory-implement/`) — One item, test first, gates green.
4. **Verify** (`.claude/skills/factory-verify/`) — Independent verifier reads the diff cold.
5. **Ship** — Human reviews PR; merge only when tests pass.

## Durable Evidence Rules

- Every agent action produces an artifact (file, comment, issue update).
- Every PR references at least one Linear issue.
- Every failure produces a `factory-failure` log in `.factory/logs/`.
- No agent may paraphrase a verdict line from `gates.sh`.

## Policy — Factory 2 (Debug Environment)

- **Only ONE GitHub Actions job** runs at any time.
- Agents: `debug-correctness`, `debug-security`, `debug-quality`, `debug-architecture`, `debug-critic`.
- Each agent finds: dead code, incomplete code, dead buttons, missing tests.
- Manager role: accepts or rejects feedback; never discounts automatically.
- All findings go to Linear + Notion database.
- **No padding** — No identical copies of files with only a number changed. Every directory must be referenced by code or CI.
