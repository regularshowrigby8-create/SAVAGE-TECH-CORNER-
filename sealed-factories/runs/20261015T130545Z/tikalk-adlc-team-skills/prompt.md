# Factory stage: tikalk-adlc-team-skills

Source factory: tikalk/adlc-team-skills (https://github.com/tikalk/adlc-team-skills), licence MIT,
commit 6cecb079e8dc305735e19657eefa209b5cc1f3c5.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- skills/team/team-init/scripts/team-init.sh
- skills/team/team-levelup/scripts/scrub-session.sh
- skills/team/team-levelup/scripts/team-levelup.sh
- skills/team/team-setup/references/post-setup-mcp.md
- skills/team/team-skills/scripts/bash/setup-team.sh
- skills/team/templates/cdr-draft-template.md

## Checks this factory defines

- A run of this skill is done when:

## Prompt assets this factory ships

- skills/architect/templates/adr-draft-template.md
- skills/authoring/writing-skills/templates/SKILL-template.md
- skills/change/templates/chdr-draft-template.md
- skills/evals/evals-analyze/scripts/bash/setup-evals-analyze.sh
- skills/evals/evals-clarify/scripts/bash/setup-evals-clarify.sh
- skills/evals/evals-clarify/scripts/powershell/setup-evals-clarify.ps1
- skills/evals/evals-implement/scripts/bash/setup-evals-implement.sh
- skills/evals/evals-init/scripts/bash/setup-evals-init.sh
- skills/evals/evals-init/scripts/powershell/setup-evals-init.ps1
- skills/evals/evals-specify/scripts/bash/setup-evals-specify.sh
- skills/evals/evals-specify/scripts/powershell/setup-evals-specify.ps1
- skills/evals/evals-templates/eval-draft-template.md
- skills/evals/evals-validate/scripts/bash/setup-evals-validate.sh
- skills/factory/factory-setup/references/mode-detect.md
- skills/factory/factory-setup/references/mode-pick.md
- skills/product/product-analyze/scripts/powershell/setup-product-analyze.ps1
- skills/product/templates/pdr-draft-template.md
- skills/product/templates/sections/goals.md
- skills/product/templates/sections/problem.md
- skills/team/team-init/scripts/team-init.sh
- skills/team/team-levelup/scripts/scrub-session.sh
- skills/team/team-levelup/scripts/team-levelup.sh
- skills/team/team-setup/references/post-setup-mcp.md
- skills/team/team-skills/scripts/bash/setup-team.sh

## What its own definition files describe

- Lightweight ADR draft — written by team-boot continuous capture.
- {short title, representative of the solved problem and the found solution}
- Context
- Decision
- Rejected Alternatives
- Reason
- Revisit When
- {{Skill Name}}
- Overview
- When to Use
- Core Process
- Red Flags
- Configuration
- Lightweight ChDR draft — written by team-boot continuous capture.
- ChDR-NNN: {short title}
- setup-evals-analyze.sh — Setup for evals-init (self-contained)
- setup-evals-clarify.sh — Setup for evals-init (self-contained)
- setup-evals-clarify.ps1 — Setup for evals-init (self-contained)
- setup-evals-implement.sh — Setup for evals-init (self-contained)
- setup-evals-init.sh — Setup for evals-init (self-contained)
- setup-evals-init.ps1 — Setup for evals-init (self-contained)
- setup-evals-specify.sh — Setup for evals-init (self-contained)
- setup-evals-specify.ps1 — Setup for evals-init (self-contained)
- Lightweight EVAL draft — written by team-boot continuous capture.

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
