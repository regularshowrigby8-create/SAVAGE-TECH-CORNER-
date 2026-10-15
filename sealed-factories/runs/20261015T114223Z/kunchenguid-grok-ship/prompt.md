# Factory stage: kunchenguid-grok-ship

Source factory: kunchenguid/grok-ship (https://github.com/kunchenguid/grok-ship), licence MIT,
commit 87825cca5c16c1c5da4f532676bccd5c3342918b.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- or teammate messages (`[agent]`, another bot reporting in)

## Checks this factory defines

- scope: branch changes between base and the pushed tip

## Prompt assets this factory ships

- skills/14-day-stale-pr-close/SKILL.md
- skills/adversarial-review/SKILL.md
- skills/ahoy/SKILL.md
- skills/lavish-session/SKILL.md
- skills/project-management/SKILL.md
- skills/triage-eligible-fetch/SKILL.md
- skills/triage-eligible-fetch/fetch.py
- skills/vision-md-triage-verdict/SKILL.md

## What its own definition files describe

- What it is
- Features
- Quick Start
- The factory files a ship task. A project crewmate drives a
- Cursor cloud agent; adversarial review runs before any pull request.
- How It Works
- License
- 14-day stale PR close
- Parameters
- Clock
- Do not close
- Required comment
- Do not
- Adversarial review
- Who runs it
- Prompt
- Loop
- Ahoy
- What counts as a captain message
- Recap
- Decisions
- Lavish Editor
- When to use
- Workflow

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
