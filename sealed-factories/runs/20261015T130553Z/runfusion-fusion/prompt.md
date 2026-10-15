# Factory stage: runfusion-fusion

Source factory: Runfusion/Fusion (https://github.com/Runfusion/Fusion), licence MIT,
commit 817566522c4422db0ab649a87ef8b6e8a690d790.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- by-step guidance for common flow

## Workers this factory defines

- packages/core/src/agents/agent-companies-types.ts
- packages/core/src/agents/agent-log-constants.ts
- packages/core/src/agents/agent-memory-mode.ts
- packages/core/src/agents/code-review-policy.ts
- packages/core/src/agents/index.ts
- packages/core/src/agents/memory-agent-defaults.ts
- packages/core/src/agents/session-advisor.ts
- packages/core/src/agents/task-execution-task-creation.ts
- packages/core/src/types/agents/index.ts
- packages/dashboard/app/api/agents/index.ts
- packages/engine/src/agents/agent-usage-telemetry.ts
- packages/engine/src/agents/approval-mail.ts
- packages/engine/src/agents/heartbeat-procedure-resolver.ts
- packages/engine/src/agents/heartbeat-prompt-trim.ts
- packages/engine/src/agents/index.ts
- packages/engine/src/agents/planning-liveness.ts
- guidance

## Prompt assets this factory ships

- packages/cli/skill/computer-use/SKILL.md
- packages/cli/skill/fusion/references/skill-patterns.md
- plugins/fusion-plugin-compound-engineering/src/skills/ce-code-review/references/action-class-rubric.md
- plugins/fusion-plugin-compound-engineering/src/skills/ce-code-review/references/diff-scope.md
- plugins/fusion-plugin-compound-engineering/src/skills/ce-commit-push-pr/references/branch-creation.md
- plugins/fusion-plugin-compound-engineering/src/skills/ce-compound/assets/resolution-template.md
- plugins/fusion-plugin-compound-engineering/src/skills/ce-resolve-pr-feedback/scripts/reply-to-pr-thread
- plugins/fusion-plugin-compound-engineering/src/skills/ce-resolve-pr-feedback/scripts/resolve-pr-thread
- plugins/fusion-plugin-compound-engineering/src/skills/ce-work/references/non-code-execution.md

## What its own definition files describe

- When to engage
- Resolve the CLI for this session
- Load the full guide before running commands
- Keep this stub thin
- Older-binary fallback
- Skill Patterns Analysis
- Patterns Observed from High-Quality Skills
- 1. Router Pattern (create-skill)
- 2. Command Reference Pattern (agent-browser)
- 3. Search & Discover Pattern (find-skills)
- Key Takeaways for Fusion Skill
- `autofix_class` rubric (personas)
- Owner field
- Diff Scope Rules
- Scope Discovery
- Remote scope (`pr-remote` and `branch-remote`)
- Finding Classification Tiers
- Primary (directly changed code)
- Secondary (immediately surrounding code)
- Pre-existing (unrelated to this diff)
- Branch creation from default branch
- Decision flow
- 1. Fetch fresh remote base
- 2. Check for unpushed local commits on `<base>`

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
