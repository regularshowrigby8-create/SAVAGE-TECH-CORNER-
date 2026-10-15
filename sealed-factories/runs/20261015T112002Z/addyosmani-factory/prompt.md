# Factory stage: addyosmani-factory

Source factory: addyosmani/factory (https://github.com/addyosmani/factory), licence MIT,
commit 8af116567166a0a16588b7ab1b9934ece0b775bc.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- template/.claude/agents/factory-critic.md
- template/.claude/agents/factory-verifier.md

## Checks this factory defines

- the item against `LOAD_BEARING` yourself. Triage can be wrong. If the work turns
- Verifier verdict: accepted
- health.** Run `./.claude/scripts/gates.sh deep` on the default branch. A gate
- 1 - Product (`01-product.md`)
- 2 - Architecture (`02-architecture.md`)
- 3 - Program design (`03-design.md`)
- 4 - Slices (`04-slices.md`)
- out the PR branch.
- ** (load-bearing changes only)

## Prompt assets this factory ships

- template/.agents/skills/factory-implement/SKILL.md
- template/.agents/skills/factory-monitor/SKILL.md
- template/.agents/skills/factory-spec/SKILL.md
- template/.agents/skills/factory-status/SKILL.md
- template/.agents/skills/factory-triage/SKILL.md
- template/.agents/skills/factory-tune/SKILL.md
- template/.agents/skills/factory-verify/SKILL.md
- template/.claude/skills/factory-implement/SKILL.md
- template/.claude/skills/factory-monitor/SKILL.md
- template/.claude/skills/factory-spec/SKILL.md
- template/.claude/skills/factory-triage/SKILL.md
- template/.claude/skills/factory-verify/SKILL.md

## What its own definition files describe

- Contributor guidance for Codex
- Factory: a reference software factory
- What this gives you
- The mental model
- How an issue becomes a reviewed pull request
- Where the human stays in the loop
- How work starts from GitHub
- Where Claude Code and Codex fit
- Start here
- Install
- What gets installed
- The four ideas doing the work
- The two constraints most people delete
- Before you build on this
- Validate the reference
- License
- Further reading
- Factory implementation for Codex
- Factory monitoring for Codex
- Factory specification for Codex
- Factory status for Codex
- Factory triage for Codex
- Factory tuning for Codex
- Factory PR verification for Codex

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
