# Factory stage: fabro-sh-fabro

Source factory: fabro-sh/fabro (https://github.com/fabro-sh/fabro), licence MIT,
commit de7ad06de5307012fc568df6b7d90a9a938d6f6c.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- .fabro/workflows/code-review/prompts/finder.md.j2
- .fabro/workflows/code-review/prompts/group-files.md.j2
- .fabro/workflows/code-review/prompts/partials/finding-fields.md.j2
- .fabro/workflows/code-review/prompts/partials/guidance.md.j2
- .fabro/workflows/code-review/prompts/partials/output-schema.md.j2
- .fabro/workflows/code-review/prompts/partials/read-only-explorer.md.j2
- .fabro/workflows/code-review/prompts/partials/review-target.md.j2
- .fabro/workflows/code-review/prompts/partials/safe-git-history.md.j2
- .fabro/workflows/code-review/prompts/sweep.md.j2
- .fabro/workflows/code-review/prompts/verify.md.j2
- .fabro/workflows/goal/prompts/audit.md
- .fabro/workflows/goal/prompts/continue.md
- .fabro/workflows/implement-plan/prompts/simplify.md
- 1: Identify Changes
- 2: Launch Three Review Agents in Parallel
- 3: Fix Issues
- .fabro/workflows/patch-cves/prompts/patch-cves.md
- .fabro/workflows/pr-simplify/prompts/simplify.md
- 1: Identify the changes
- 2: Launch three review sub-agents in parallel
- 3: Apply fixes
- 4: Update the existing PR

## Workers this factory defines

- 1: Code Reuse Review
- 2: Code Quality Review
- 3: Efficiency Review

## Checks this factory defines

- Run verification gates scaled to the touched ecosystem and repo conventions:
- commands and results
- run locally

## Prompt assets this factory ships

- .ai/prompts/code-review-deep-1.md
- .ai/prompts/code-review-deep-2.md
- .ai/prompts/code-review-deep-3.md
- .ai/prompts/code-review-fast.md
- .claude/skills/changelog/watermark
- .claude/skills/docs/watermark
- .fabro/workflows/code-review/prompts/finder.md.j2
- .fabro/workflows/code-review/prompts/group-files.md.j2
- .fabro/workflows/code-review/prompts/partials/finding-fields.md.j2
- .fabro/workflows/code-review/prompts/partials/guidance.md.j2
- .fabro/workflows/code-review/prompts/partials/output-schema.md.j2
- .fabro/workflows/code-review/prompts/partials/read-only-explorer.md.j2
- .fabro/workflows/code-review/prompts/partials/review-target.md.j2
- .fabro/workflows/code-review/prompts/partials/safe-git-history.md.j2
- .fabro/workflows/code-review/prompts/sweep.md.j2
- .fabro/workflows/code-review/prompts/verify.md.j2
- .fabro/workflows/goal/prompts/audit.md
- .fabro/workflows/goal/prompts/continue.md
- .fabro/workflows/implement-plan/prompts/simplify.md
- .fabro/workflows/patch-cves/prompts/patch-cves.md
- .fabro/workflows/pr-simplify/prompts/simplify.md
- docs/internal/demo/prompts/simplify.md
- lib/apps/fabro-server/src/prompts/run_title.md.j2
- lib/apps/fabro-server/src/server/handler/prompts/ask_fabro.md.j2

## What its own definition files describe

- Simplify: Code Review and Cleanup
- Eng Patch CVEs
- Overview
- Baseline
- Query Alerts
- Rank And Group
- Choose The Fix
- Ecosystem Rules
- Branch And Patch
- PR Format
- Final Report
- Reviewer 1 — Code Reuse
- Reviewer 2 — Code Quality
- Reviewer 3 — Efficiency
- Done

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
