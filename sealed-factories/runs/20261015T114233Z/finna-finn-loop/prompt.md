# Factory stage: finna-finn-loop

Source factory: finna/Finn-loop (https://github.com/finna/Finn-loop), licence MIT,
commit 7941b62c946154d15c11b7f24931bb8b6e155f01.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Checks this factory defines

- `claude --version` is 2.1.71 or newer. Check that the Linear
- `gh auth status` and `gh repo view` both work. Detect the repository's
- criteria are observable outcomes; non-goals are binding. A PR
- only against the linked issue: acceptance-criteria gaps, defects,
- Summary: one or two plain-language sentences on what this PR does.
- Criteria

## Prompt assets this factory ships

- skills/finn-build/SKILL.md
- skills/finn-review/SKILL.md
- skills/finn-spec/SKILL.md

## What its own definition files describe

- Finn-loop
- Requirements
- Install
- Daily rhythm (~15 minutes)
- What `loop-approved` means
- The rules that make it work
- From the starter loop to a full software factory
- 1. Make build and review self-converging
- 2. Use Slack as a human control plane
- 3. Add risk-aware merge policy carefully
- 4. Use Linear relations for multi-part features
- 5. Turn previews and documentation into merge gates
- 6. Add direction and status skills
- 7. Add a factory watchdog
- 8. Run persistent workers with leases
- 9. Build a post-merge learning loop
- 10. Next experiment: approve specs from Slack
- Suggested implementation order
- Finn-loop builder
- 0. Preflight
- 1. Review feedback first
- 2. Pick
- 3. Claim (the cooperative lock)
- 4. Read

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
