# Factory stage: cleanwk-agent-pipeline-example

Source factory: cleanwk/agent-pipeline-example (https://github.com/cleanwk/agent-pipeline-example), licence Apache-2.0,
commit 0af8c6cb46f30ae541d464bb729eedb835295397.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- pipelines/product-delivery.yaml

## Workers this factory defines

- Pipeline Example
- id: grill
- id: ticket
- id: spec
- id: implement
- id: review
- id: deploy
- id: smoke

## Prompt assets this factory ships

- prompts/grill.md
- prompts/implement.md
- prompts/review.md
- prompts/smoke.md
- prompts/spec.md

## What its own definition files describe

- Layout
- Install

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
