# Factory stage: professorpalmer-puppetmaster

Source factory: professorpalmer/Puppetmaster (https://github.com/professorpalmer/Puppetmaster), licence MIT,
commit d066116672864e72563dda31d3e1f1f94499328c.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- fallback is one command.** On `Not connected`, run

## Checks this factory defines

- gate tunables. The judge sees a bounded slice of the diff (a judge
- gate — a strictly-stronger model judges the diff before COMPLETE

## Prompt assets this factory ships

- .cursor/skills/verify-puppetmaster-jev/SKILL.md
- .cursor/skills/verify-puppetmaster-jev/features/README.md
- .cursor/skills/verify-puppetmaster-jev/features/act.md
- .cursor/skills/verify-puppetmaster-jev/features/observe.md
- .cursor/skills/verify-puppetmaster-jev/features/unset.md
- .cursor/skills/verify-puppetmaster-jev/features/v1-on-opt-in.md
- .cursor/skills/verify-puppetmaster-jev/features/v2-on-job.md
- .cursor/skills/verify-puppetmaster-jev/scripts/doctor.py
- .cursor/skills/verify-puppetmaster-jev/scripts/prove_observe.py
- bench/prompts/example.txt
- puppetmaster/pi_package/prompts/puppetmaster-pilot.md
- puppetmaster/pi_package/skills/puppetmaster-pilot/SKILL.md
- puppetmaster/skills/puppetmaster/SKILL.md
- puppetmaster/skills/puppetmaster/references/monitoring-state-machine.md
- puppetmaster/skills/puppetmaster/references/recovery.md

## What its own definition files describe

- First: are you a Puppetmaster worker?
- Puppetmaster is the fan-out runtime
- When to start a Puppetmaster swarm
- When you MUST NOT route through Puppetmaster
- How to drive a started swarm
- Model routing (auto_route)
- Repo intelligence (CodeGraph)
- Why Puppetmaster for fan-out
- When MCP fails: fall back to the CLI, don't give up
- Verify Puppetmaster Jev
- Launch
- Doctor
- Drive
- Evidence
- Cleanup
- Helpers
- Feature map
- Baseline
- Proof
- Features
- ACT experiment skips
- Sub-features
- How to get to it (user POV)
- Driving it with unittest

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
