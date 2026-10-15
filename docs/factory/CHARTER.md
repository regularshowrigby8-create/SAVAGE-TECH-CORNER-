# SAVAGE TECH CORNER — GLOBAL CHARTER

This charter applies to EVERYTHING in this repository, across ALL branches (including `main`, `production`, `shell`, `frontend`, `backend`, and `pipeline`).

## Non-Negotiable Rules (Every Branch, Every PR)

1. **No dead code** — Any unreachable, unused, or orphaned function must be flagged and removed.
2. **No incomplete code** — Partial implementations must include a `// TODO: INCOMPLETE` comment and a Linear issue.
3. **No dead buttons** — Every interactive element must have a verified handler.
4. **Every PR must pass the Factory Gate** — `gates.sh` runs before merge.
5. **One job per factory** — Factory 2 runs exactly ONE GitHub Actions job at a time.
6. **Feedback is mandatory** — All agent feedback must be documented, accepted or rejected in writing.
7. **No padding** — No identical copies of the same file with only a number changed. No directories that aren't referenced by code or executed by CI. Every file must have a real purpose and be reachable from the pipeline or repo structure.
8. **Every claim must reference a literal path** — See `docs/factory/ENFORCEMENT.md` (rules 1–6, including claim-checking in `gates.sh`).
9. **Universal ZIP Change Ledger Rule** — Every folder (big and small) across the repository that undergoes modifications must maintain a detailed, verified changelog (`CHANGES.md`) tracking all additions, edits, and deletions with clarity, timestamps, and direct verification evidence. The canonical archive (`factories/factory-setup.zip`) must include these verified change ledgers for all subtrees.
10. **Direct Verification Invariant** — No change may be recorded or committed without direct automated verification (clean syntax compilation, test execution pass, and zero gate failures). Speculative or unverified claims are strictly barred from `main` and all production paths.

## Branch Rules

| Branch       | Purpose                         | Rules                                                                   |
| ------------ | ------------------------------- | ----------------------------------------------------------------------- |
| `main`       | Production release baseline     | All 5 pillars; full strict charter enforcement; load-bearing paths only |
| `production` | Certified releases              | Only certified release artifacts, manifests, and verified APKs          |
| `shell`      | Soul browser-style architecture | Shell components, WebViews, Android entry points                        |
| `frontend`   | Android browser frontend        | Compose UI, controller, tabs, local storage, API client                 |
| `backend`    | Browser backend services        | Gateway, search orchestration, AI services, caching, workers            |
| `pipeline`   | CI automation & multi-factory   | Multi-factory runners, model discovery workflows                        |

## Human-Owned Tier (Load-Bearing)

- `docs/factory/CHARTER.md` — This file
- `docs/factory/CONTRACT.md` — Queue protocol
- `.github/workflows/factory-2-debug.yml` — The gate
- `.claude/scripts/gates.sh` — Deterministic checks

## Automatable Work

- Issue triage, spec generation, test writing, code review, reflection.
- Nothing merges without CI + independent verification.
