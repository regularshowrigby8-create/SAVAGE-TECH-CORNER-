# Factory stage: udoschneider-workflow-pipeline

Source factory: udoschneider/workflow-pipeline (https://github.com/udoschneider/workflow-pipeline), licence MIT,
commit f13b7af7e16406fb3d8e265876b91ffcdcd5c740.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Search `test/` for related test files
- 2: Read referenced source files, check for rationale comments
- 3: Read `project/lessons.md`, check for unextracted content
- 4: Check if lessons.md entries should be promoted to code

## Checks this factory defines

- criteria

## Prompt assets this factory ships

- skills/audit-thoughts/SKILL.md
- skills/process-inbox/SKILL.md
- skills/process-scrap/SKILL.md
- skills/prune-completed/SKILL.md
- skills/prune-lessons/SKILL.md
- skills/what-next/SKILL.md

## What its own definition files describe

- Workflow Pipeline
- workflow-pipeline
- What you get
- What isn't included
- Install
- As a Claude Code plugin
- With OpenPackage
- By hand
- OpenCode, or any agent that resolves /name from a commands directory:
- Wiring — the step that decides whether any of this runs
- After installing
- The prior-art sweep
- Keeping the indexes fresh
- Repository layout
- Developing this package
- License
- Status
- Audit Thoughts Skill
- Invocation
- Why this skill exists
- Scope
- Per-match action
- Workflow
- 1. Build the candidate pool

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
