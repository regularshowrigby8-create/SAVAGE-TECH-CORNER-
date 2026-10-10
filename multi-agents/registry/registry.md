# Multi-Agent Registry — 50 Agents

All 50 agents registered. Each has:
- A workspace file (`multi-agents/workspaces/agent-XX.md`)
- A role defined in the registry below
- Connection to Linear/Notion/Gmail for reporting

Agents run in sequence or parallel depending on the single-job policy (`.github/workflows/factory-2-debug.yml`).

| Agent | Role | Evidence Source |
|---|---|---|
| 01 | correctness-verifier | Source code scan |
| 02 | security-auditor | Regex + file scan |
| 03 | architecture-reviewer | Directory + reference scan |
| 04 | quality-checker | Test/pipeline file check |
| 05 | critic-adversarial | Real report review |
| 06-50 | specialized agents | Refer to workspace files |
