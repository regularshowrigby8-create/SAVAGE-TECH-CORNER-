# Claude Code Contributor Instructions — SAVAGE TECH CORNER

## Workspace Context

- Main rules: `docs/factory/CHARTER.md`
- Contract: `docs/factory/CONTRACT.md`
- Gates: `.claude/scripts/gates.sh`

## Skills

- `.claude/skills/factory-triage/` — Issue triage with Linear labels
- `.claude/skills/factory-spec/` — Four gates before code
- `.claude/skills/factory-implement/` — One item, test first
- `.claude/skills/factory-verify/` — Independent verification
- `.claude/skills/factory-monitor/` — Weekly sweep

## Agents

- `.claude/agents/factory-critic.md` — Adversarial review
- `.claude/agents/factory-verifier.md` — Independent cold-read verification

## Commands

Always run the gate before committing:

```bash
bash .claude/scripts/gates.sh
```

## Factory 2 (Debug Agents)

Agents run as ONE GitHub Actions job. They never modify code directly — they file Linear issues and write to Notion database.
