# Factory stage: shenwell-ai-software-factory-pipeline

Source factory: shenwell/ai-software-factory-pipeline (https://github.com/shenwell/ai-software-factory-pipeline), licence MIT,
commit e8529c3fe54a300f61fa9edac08ff401f03c5d70.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1 — Workspace layout
- 2 — Git
- 3 — GitHub CLI
- 4 — Resolve owner/repo
- 5 — Create repository (`create` path only)
- 6 — Factory label
- 7 — Workflow file
- 8 — Python (UI jobs)
- 9 — Config touch-up
- 10 — Board bootstrap
- 11 — Write setup record
- 12 — Report to the user
- 1 — Fetch upstream kit (consumer only)
- 2 — Copy kit-managed paths (overwrite)
- 3 — Never overwrite (preserve local state)
- 4 — Merge `factory.config.json`
- 5 — Merge `AGENTS.md`
- 6 — Git (consumer)
- 7 — Write upgrade record
- 8 — Report to the user

## Checks this factory defines

- the real diff
- in this order
- for repetitive sentence structures

## Prompt assets this factory ships

- .cursor/skills/analyst/SKILL.md
- .cursor/skills/classifier/SKILL.md
- .cursor/skills/designer/SKILL.md
- .cursor/skills/factory-pipeline/SKILL.md
- .cursor/skills/factory-pipeline/references/chat-auto-start.md
- .cursor/skills/factory-pipeline/references/decomposition.md
- .cursor/skills/factory-pipeline/references/run-report.md
- .cursor/skills/factory-setup/SKILL.md
- .cursor/skills/factory-upgrade/SKILL.md
- .cursor/skills/frontend-design/SKILL.md
- .cursor/skills/github-intake/SKILL.md
- .cursor/skills/implementer/SKILL.md
- .cursor/skills/researcher/SKILL.md
- .cursor/skills/reviewer/SKILL.md
- .cursor/skills/reviewer/references/ui-gate.md
- .cursor/skills/triaging-issues/SKILL.md
- .cursor/skills/triaging-issues/references/repro-request-structure.md
- .cursor/skills/ui-ux-pro-max/data/catalog-summary.json
- .cursor/skills/ui-ux-pro-max/references/pro-rules.md
- .cursor/skills/ui-ux-pro-max/scripts/reasoning_contract.py
- .cursor/skills/ui-ux-pro-max/scripts/search.py
- .cursor/skills/writing-quality/SKILL.md
- .cursor/skills/writing-quality/references/ai-phrases-to-avoid.md
- .cursor/skills/writing-quality/references/plain-english-alternatives.md

## What its own definition files describe

- Software factory
- Analyst
- Ground the plan in the checkout
- Produce
- Decomposition
- Classifier
- Designer
- When you run
- How to design
- Artifact
- Factory pipeline
- Chat auto-start
- How you write
- Policy
- Ensure issue (free text only)
- Multi-phase runs
- Researcher
- Decomposition (after analyst)
- Implementer
- Reviewer
- Deliver
- Run report
- Artifacts
- Queue, runs, board

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
