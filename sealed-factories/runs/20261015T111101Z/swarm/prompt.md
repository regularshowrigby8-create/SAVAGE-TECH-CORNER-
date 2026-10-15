# Factory stage: swarm

Source factory: openai/swarm (https://github.com/openai/swarm), licence MIT,
commit 6af0b4caf37dca4526dfd98e9fbd8ce36e7eeb22.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- (experimental, educational)
- `Agent`s can call python functions directly.
- examples/customer_service_streaming/src/swarm/assistants.py
- examples/customer_service_streaming/src/swarm/conversation.py
- examples/customer_service_streaming/src/swarm/engines/assistants_engine.py
- examples/customer_service_streaming/src/swarm/engines/engine.py
- examples/customer_service_streaming/src/swarm/engines/local_engine.py
- examples/customer_service_streaming/src/swarm/swarm.py
- examples/customer_service_streaming/src/swarm/tool.py
- swarm/__init__.py
- swarm/core.py
- swarm/repl/__init__.py
- swarm/repl/repl.py
- swarm/types.py
- swarm/util.py

## Checks this factory defines

- for exit command

## What its own definition files describe

- Install
- Usage
- Table of Contents
- Overview
- Why Swarm
- Examples
- Documentation
- Running Swarm
- `client.run()`
- Arguments
- `Response` Fields
- Agents
- `Agent` Fields
- Instructions
- Functions
- Handoffs and Updating Context Variables
- Function Schemas
- Streaming
- Evaluations
- Utils
- Core Contributors

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
