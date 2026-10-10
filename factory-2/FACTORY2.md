# FACTORY 2 — DEBUG ENVIRONMENT

Only ONE GitHub Actions job runs at any time (`.github/workflows/factory-2-debug.yml`).

## AI Debug Agents (Multiple Models)

| Agent | Role | Model Type |
|---|---|---|
| `agent-correctness` | Finds dead/incomplete code | Reasoning model |
| `agent-security` | Finds vulnerable patterns | Security model |
| `agent-architecture` | Reviews structure | Architecture model |
| `agent-quality` | Checks tests, coverage | Quality model |
| `agent-critic` | Adversarial review | Critic model |

## Manager Role
The manager (`manager/guidance.py`) guides agents and accepts/rejects feedback. Never discounts automatically.

## Rules
- Every agent writes to `factory-2/reports/`.
- Every finding goes to Linear + Notion.
- Manager must respond to each finding in writing.
- No automatic discounts.
