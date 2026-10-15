# Factory stage: mcp-agent

Source factory: lastmile-ai/mcp-agent (https://github.com/lastmile-ai/mcp-agent), licence Apache-2.0,
commit f62d849350816588b1c6294e7914bbe4d8b84072.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- src/mcp_agent/workflows/orchestrator/orchestrator.py
- src/mcp_agent/workflows/orchestrator/orchestrator_models.py
- src/mcp_agent/workflows/orchestrator/orchestrator_prompts.py
- src/mcp_agent/workflows/swarm/swarm.py
- src/mcp_agent/workflows/swarm/swarm_anthropic.py
- src/mcp_agent/workflows/swarm/swarm_openai.py

## Workers this factory defines

- examples/tracing/agent/README.md
- examples/tracing/agent/main.py
- examples/tracing/agent/mcp_agent.config.yaml
- examples/tracing/agent/mcp_agent.secrets.yaml.example
- examples/tracing/agent/requirements.txt
- src/mcp_agent/agents/agent_spec.py
- =Agent(
- src/mcp_agent/workflows/swarm/swarm.py
- src/mcp_agent/workflows/swarm/swarm_anthropic.py
- src/mcp_agent/workflows/swarm/swarm_openai.py

## What its own definition files describe

- Initialize logging
- MCP Agent example
- Exporting to Collector
- Settings loaded from mcp_agent.config.yaml/mcp_agent.secrets.yaml
- Core framework dependency
- Additional dependencies specific to this example

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
