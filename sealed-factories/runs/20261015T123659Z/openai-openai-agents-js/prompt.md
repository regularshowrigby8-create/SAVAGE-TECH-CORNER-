# Factory stage: openai-openai-agents-js

Source factory: openai/openai-agents-js (https://github.com/openai/openai-agents-js), licence MIT,
commit 89a9ddf1e241e75f2e0ad4c15d89de9e215ebac6.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- .agents/skills/changeset-validation/agents/openai.yaml
- .agents/skills/docs-sync/agents/openai.yaml
- .agents/skills/final-release-review/agents/openai.yaml
- .agents/skills/implementation-final-review/agents/openai.yaml
- .agents/skills/implementation-kickoff/agents/openai.yaml
- .agents/skills/openai-knowledge/agents/openai.yaml
- .agents/skills/pnpm-upgrade/agents/openai.yaml
- .agents/skills/pr-draft-summary/agents/openai.yaml
- .agents/skills/sensitive-logging-audit/agents/openai.yaml
- .agents/skills/test-coverage-improver/agents/openai.yaml
- examples/docs/agents/agentCloning.ts
- examples/docs/agents/agentWithLifecycleHooks.ts
- examples/docs/agents/agentWithZodOutputType.ts
- examples/docs/agents/simpleAgent.ts
- integration-tests/cloudflare-workers/worker/.npmrc
- packages/agents/src/realtime/index.ts
- packages/agents/src/realtime/testing/index.ts
- packages/agents/src/sandbox/index.ts
- packages/agents/src/sandbox/local.ts
- packages/agents/src/testing/index.ts
- packages/agents/src/utils/index.ts
- packages/agents/tsconfig.json
- packages/agents/tsconfig.test.json

## Prompt assets this factory ships

- .agents/skills/changeset-validation/agents/openai.yaml
- .agents/skills/code-change-verification/scripts/run.ps1
- .agents/skills/code-change-verification/scripts/run.sh
- .agents/skills/docs-sync/agents/openai.yaml
- .agents/skills/final-release-review/agents/openai.yaml
- .agents/skills/implementation-final-review/agents/openai.yaml
- .agents/skills/implementation-kickoff/agents/openai.yaml
- .agents/skills/openai-knowledge/agents/openai.yaml
- .agents/skills/pnpm-upgrade/agents/openai.yaml
- .agents/skills/pr-draft-summary/agents/openai.yaml
- .agents/skills/sensitive-logging-audit/agents/openai.yaml
- .agents/skills/test-coverage-improver/agents/openai.yaml

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
