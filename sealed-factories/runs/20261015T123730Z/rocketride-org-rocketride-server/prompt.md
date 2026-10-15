# Factory stage: rocketride-org-rocketride-server

Source factory: rocketride-org/rocketride-server (https://github.com/rocketride-org/rocketride-server), licence MIT,
commit bd9b6fb000d494c7e4124f3efbc88b556b7ae80a.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- packages/server/engine-lib/engLib/store/pipeline/pipeline_config.hpp
- packages/server/engine-lib/engLib/store/pipeline/validate_pipeline.hpp
- pipelines/exa-search-working.pipe
- pipelines/text_file_to_audio.pipe

## Workers this factory defines

- apps/vscode/src/agents/agents-md-installer.ts
- apps/vscode/src/agents/claude-code-installer.ts
- apps/vscode/src/agents/claude-md-installer.ts
- apps/vscode/src/agents/copilot-installer.ts
- apps/vscode/src/agents/cursor-installer.ts
- apps/vscode/src/agents/services.ts
- apps/vscode/src/agents/windsurf-installer.ts
- docs/agents/README.md
- docs/agents/skills/README.md
- docs/agents/skills/rocketride-designing-pipelines/LAYER1_NODE_INDEX.meta.json
- docs/agents/skills/rocketride-designing-pipelines/examples/FAILURE_SCENARIOS.md
- docs/agents/skills/rocketride-designing-pipelines/examples/README.md
- docs/agents/skills/rocketride-designing-pipelines/examples/agentic-chat.pipe
- docs/agents/skills/rocketride-designing-pipelines/examples/document-ingestion.pipe
- docs/agents/skills/rocketride-designing-pipelines/examples/simple-chat-rag.pipe
- packages/ai/src/ai/common/agent/__init__.py

## Prompt assets this factory ships

- docs/agents/skills/README.md
- docs/agents/skills/rocketride-designing-pipelines/LAYER1_NODE_INDEX.meta.json
- docs/agents/skills/rocketride-designing-pipelines/examples/FAILURE_SCENARIOS.md
- docs/agents/skills/rocketride-designing-pipelines/examples/README.md
- docs/agents/skills/rocketride-designing-pipelines/examples/agentic-chat.pipe
- docs/agents/skills/rocketride-designing-pipelines/examples/document-ingestion.pipe
- docs/agents/skills/rocketride-designing-pipelines/examples/simple-chat-rag.pipe
- nodes/src/nodes/prompt/IGlobal.py
- nodes/src/nodes/prompt/IInstance.py
- nodes/src/nodes/prompt/README.md
- nodes/src/nodes/prompt/__init__.py
- nodes/src/nodes/prompt/services.json

## What its own definition files describe

- docs/agents/
- Skills
- The pipeline-builder skill set
- Failure Scenarios (what NOT to do)
- ❌ Fabricating a node that isn't in the index
- ❌ Wiring a lane that doesn't match
- ❌ Guessing a config field
- ❌ Putting the control array on the agent
- ❌ Claiming success without checking
- ❌ Self-approving a dismissed gate
- ❌ Hardcoding a secret
- ❌ Blocking the event loop when running
- Worked Examples
- 1. `simple-chat-rag.pipe` — answer questions from indexed docs (RAG)
- 2. `document-ingestion.pipe` — load docs into the vector store
- 3. `agentic-chat.pipe` — an agent with an LLM + memory (control plane)
- prompt
- What it does
- Lanes
- Configuration
- Instructions
- Requirements
- Notes
- Rendered prompt structure

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
