# Factory stage: vercel-labs-eve-software-factory-template

Source factory: vercel-labs/eve-software-factory-template (https://github.com/vercel-labs/eve-software-factory-template), licence MIT,
commit 6bc5febab95de4c05fa08b164f544650f6aa11aa.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- evals/pipeline/full-pipeline.eval.ts

## Workers this factory defines

- agent/agent.ts
- agent/channels/eve.ts
- agent/lib/github/credentials.ts
- agent/lib/models.ts
- agent/subagents/analyst/sandbox.ts
- agent/subagents/analyst/tools/glob.ts
- agent/subagents/analyst/tools/grep.ts
- agent/subagents/analyst/tools/read_artifact.ts
- agent/subagents/analyst/tools/save_artifact.ts
- agent/subagents/classifier/instructions.md
- agent/subagents/implementer/sandbox.ts
- agent/subagents/implementer/tools/glob.ts
- agent/subagents/implementer/tools/grep.ts
- agent/subagents/implementer/tools/read_artifact.ts
- agent/subagents/researcher/tools/save_artifact.ts
- agent/subagents/reviewer/sandbox.ts
- agent/subagents/reviewer/tools/glob.ts
- agent/subagents/reviewer/tools/grep.ts
- agent/subagents/reviewer/tools/read_artifact.ts
- agent/tools/agent.ts
- agent/tools/glob.ts
- agent/tools/grep.ts
- agent/tools/read_artifact.ts
- agent/tools/run_classifier.ts

## What its own definition files describe

- Classifier

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
