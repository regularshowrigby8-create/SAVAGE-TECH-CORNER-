# Factory stage: swe-agent

Source factory: SWE-agent/SWE-agent (https://github.com/SWE-agent/SWE-agent), licence MIT,
commit 3ea751c087f32b16e039a2233dd6eefecef325d5.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- ,

## Workers this factory defines

- sweagent/agent/action_sampler.py
- sweagent/agent/extra/shell_agent.py
- sweagent/agent/history_processors.py
- sweagent/agent/hooks/abstract.py
- sweagent/agent/hooks/status.py
- sweagent/agent/models.py
- sweagent/agent/problem_statement.py
- sweagent/agent/reviewer.py

## Checks this factory defines

- whether total cost or instance cost limits have been exceeded

## What its own definition files describe

- The agent class
- Utility functions
- -----------------
- History processors
- ------------------
- Constants for image processing
- --- INTERFACES ---
- --- CONFIGS ---
- --- IMPLEMENTATIONS ---
- todo: Couldn't I just replace the whole thing with Jinja templates?

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
