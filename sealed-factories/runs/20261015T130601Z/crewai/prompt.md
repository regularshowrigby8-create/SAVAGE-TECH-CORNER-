# Factory stage: crewai

Source factory: crewAIInc/crewAI (https://github.com/crewAIInc/crewAI), licence MIT,
commit eb15c66cef12073d8d06d6942eecc35e7fd6c783.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- lib/cli/src/crewai_cli/templates/crew/.gitignore
- lib/cli/src/crewai_cli/templates/crew/config/agents.yaml
- lib/cli/src/crewai_cli/templates/crew/config/tasks.yaml
- lib/cli/src/crewai_cli/templates/crew/crew.py
- lib/cli/src/crewai_cli/templates/crew/knowledge/user_preference.txt
- lib/cli/src/crewai_cli/templates/crew/pyproject.toml
- lib/cli/src/crewai_cli/templates/crew/tools/custom_tool.py
- lib/cli/src/crewai_cli/templates/flow/crews/content_crew/config/agents.yaml
- lib/crewai-tools/src/crewai_tools/aws/bedrock/agents/__init__.py
- lib/crewai/src/crewai/agent/__init__.py
- lib/crewai/src/crewai/agents/__init__.py
- lib/crewai/src/crewai/agents/agent_adapters/base_tool_adapter.py
- lib/crewai/src/crewai/agents/agent_adapters/langgraph/__init__.py
- lib/crewai/src/crewai/agents/agent_adapters/langgraph/protocols.py
- lib/crewai/src/crewai/agents/agent_adapters/openai_agents/__init__.py
- lib/crewai/src/crewai/agents/agent_builder/utilities/base_token_process.py
- lib/crewai/src/crewai/agents/cache/__init__.py
- lib/crewai/src/crewai/agents/constants.py
- lib/crewai/src/crewai/crews/__init__.py
- lib/crewai/src/crewai/utilities/crew/__init__.py
- lib/crewai/src/crewai/utilities/crew/crew_context.py
- lib/crewai/src/crewai/utilities/crew/models.py

## Prompt assets this factory ships

- lib/crewai/src/crewai/experimental/skills/__init__.py
- lib/crewai/src/crewai/skills/__init__.py
- lib/crewai/src/crewai/skills/events.py

## What its own definition files describe

- Keep `crewai.experimental.skills.<module>` imports (and patch targets)
- resolving to the real modules in crewai.skills.

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
