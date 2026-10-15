# Factory stage: conductor-oss-conductor

Source factory: conductor-oss/conductor (https://github.com/conductor-oss/conductor), licence Apache-2.0,
commit c52919afd0ec2ffde0196afbab10762d2d48ed28.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- common/src/main/java/com/netflix/conductor/common/metadata/workflow/IdempotencyStrategy.java

## Workers this factory defines

- ui-next/src/components/features/agent/Agent.tsx
- ui-next/src/components/features/agent/AgentEditorController.tsx
- ui-next/src/components/features/agent/agent-types.ts
- ui-next/src/components/features/agent/helpers.ts
- ui-next/src/pages/agent/executions/ApiSearchModalIntegration.test.ts
- ui-next/src/pages/agent/executions/agentSearchCode.ts
- ui-next/src/pages/agent/executions/executionsStyles.ts
- ui-next/src/pages/agent/guideMarkdown.ts
- ui-next/src/pages/agent/guides/dotnet/native.md
- ui-next/src/pages/agent/index.ts

## What its own definition files describe

- 1. Add the SDK
- 2. Configure
- For authenticated Conductor servers:
- export CONDUCTOR_AUTH_KEY=<YOUR_AUTH_KEY>
- export CONDUCTOR_AUTH_SECRET=<YOUR_AUTH_SECRET>
- 3. Run an agent

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
