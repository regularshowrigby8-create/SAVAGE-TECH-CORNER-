# Factory stage: renseiai-donmai-libraries

Source factory: RenseiAI/donmai-libraries (https://github.com/RenseiAI/donmai-libraries), licence MIT,
commit ff0b52fe1771b42582258d3e7e1502555bfedbe0.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- packages/dashboard/dev/app/pipeline/page.tsx
- packages/dashboard/src/components/pipeline/pipeline-column.tsx
- templates/dashboard/src/app/pipeline/page.tsx

## Workers this factory defines

- packages/nextjs/src/handlers/workers/list.ts
- packages/spring-ai-bench/agents/agentfactory-single.yaml
- packages/spring-ai-bench/agents/agentfactory.yaml
- templates/dashboard/src/app/api/workers/[id]/heartbeat/route.ts
- templates/dashboard/src/app/api/workers/[id]/poll/route.ts
- templates/dashboard/src/app/api/workers/[id]/route.ts
- templates/dashboard/src/app/api/workers/register/route.ts
- templates/dashboard/src/app/api/workers/route.ts

## Prompt assets this factory ships

- packages/architectural-intelligence/src/prompts/__test-helpers__.ts
- packages/architectural-intelligence/src/prompts/index.ts
- packages/architectural-intelligence/src/prompts/prompts.test.ts
- packages/architectural-intelligence/src/prompts/v1/convention-identification.ts
- packages/architectural-intelligence/src/prompts/v1/decision-recording.ts
- packages/architectural-intelligence/src/prompts/v1/deviation-detection.ts
- packages/architectural-intelligence/src/prompts/v1/pattern-extraction.ts
- templates/dashboard/src/app/api/sessions/[id]/prompts/route.ts

## What its own definition files describe

- Safe dependency addition for agents in worktrees.
- Removes symlinked node_modules, then runs pnpm add with guard bypass.
- Usage: bash .agent/add-dep.sh <package> [--filter <workspace>]

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
