# Factory stage: os-factory-har

Source factory: os-factory/har (https://github.com/os-factory/har), licence Apache-2.0,
commit 874e9a1127e00d8d07524aca6b3c52cc62270111.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- .har/lines/external-worktree/stages/external-common.sh
- .har/lines/external-worktree/stages/external-s2.sh
- .har/stages/README.sh
- .har/stages/external-common.sh
- .har/stages/external-s2.sh
- .har/stages/readiness.sh
- control/.har/stages/PLAYWRIGHT.md
- control/.har/stages/README.sh
- control/.har/stages/api-health.sh
- control/.har/stages/readiness.sh
- docs/.har/stages/README.sh
- docs/.har/stages/api-health.sh
- docs/.har/stages/readiness.sh
- src/templates/plugins/playwright/.har/stages/PLAYWRIGHT.md
- src/templates/runtime-bundles/pm2-runtime/stages/api-health.sh
- src/templates/runtime-bundles/shared-kernel/stages/README.sh
- src/templates/runtime-bundles/shared-kernel/stages/readiness.sh

## Checks this factory defines

- .har/maintain/plugins/playwright/

## Prompt assets this factory ships

- benchmarks/har-vs-raw/prompts/curate-issues.md
- benchmarks/har-vs-raw/prompts/fix-issue.md
- benchmarks/har-vs-raw/prompts/setup-har.md
- benchmarks/swebench-har/prompts/har-fix.md
- benchmarks/swebench-har/prompts/har-setup.md
- benchmarks/swebench-har/prompts/har-task-readiness.md
- benchmarks/swebench-har/prompts/raw-fix.md

## What its own definition files describe

- HAR Harness Workflow
- Shared helpers for external-worktree line stages.
- external-s2 — Teardown leaves a foreign checkout alone
- # Station S2 of the external-worktree factory line (#253).
- Registered in stages.json; deliberately ABSENT from verificationStages —
- run it with: har line gate S2 --line external-worktree
- Per-stage scripts live here (e.g. a custom `./.har/stages/<id>.sh <agent-id>`).
- # Read .har/STAGES.md for the authoring guide: the stage script contract,
- command vs script stages, and how verificationStages controls verify --full.
- # Scaffold a new one with:
- har env add-stage <id> --custom --script
- Project readiness smoke: runs HARNESS_READINESS_CMD from harness.env, an
- optional project-owned "agent usable" check beyond health. Registered as the
- `readiness` verification stage (tier: full).
- Playwright (browser-e2e)
- Run
- or included in:
- New UI features
- Optional per-stage scripts live here.
- Example: ./.har/stages/browser-e2e.sh <agent-id>
- # Register custom stages in .har/stages.json with a project-owned command:
- "command": "./.har/stages/browser-e2e.sh {agentId}"
- API health check for the slot's primary application. Registered as the
- `api-health` verification stage (tier: quick) on web profiles.

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
