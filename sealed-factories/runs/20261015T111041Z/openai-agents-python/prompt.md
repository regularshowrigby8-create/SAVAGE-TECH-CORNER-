# Factory stage: openai-agents-python

Source factory: openai/openai-agents-python (https://github.com/openai/openai-agents-python), licence MIT,
commit 1c1a28df92f359dded4eed83976301c2f9850e89.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- .agents/skills/docs-sync/agents/openai.yaml
- .agents/skills/openai-knowledge/agents/openai.yaml
- src/agents/extensions/__init__.py
- src/agents/extensions/experimental/__init__.py
- src/agents/py.typed
- src/agents/realtime/README.md
- src/agents/sandbox/instructions/prompt.md
- src/agents/sandbox/memory/prompts/memory_consolidation_prompt.md
- src/agents/sandbox/memory/prompts/memory_read_prompt.md
- src/agents/sandbox/memory/prompts/rollout_extraction_prompt.md
- src/agents/sandbox/memory/prompts/rollout_extraction_user_message.md
- src/agents/tracing/logger.py
- src/agents/util/_coro.py
- src/agents/util/_types.py
- src/agents/version.py
- src/agents/voice/exceptions.py

## Checks this factory defines

- or reporting behaviors the user expects without restating
- habits: the user’s expectations for tests/lints/sanity checks, and what
- checklist (concrete success checks)

## Prompt assets this factory ships

- .agents/skills/docs-sync/agents/openai.yaml
- .agents/skills/openai-knowledge/agents/openai.yaml
- src/agents/sandbox/instructions/prompt.md
- src/agents/sandbox/memory/prompts/memory_consolidation_prompt.md
- src/agents/sandbox/memory/prompts/memory_read_prompt.md
- src/agents/sandbox/memory/prompts/rollout_extraction_prompt.md
- src/agents/sandbox/memory/prompts/rollout_extraction_user_message.md

## What its own definition files describe

- `Skills`
- This package contains experimental extensions to the agents package.
- The interface and implementation details could be changed until being GAed.
- Realtime
- How you work
- Personality
- AGENTS.md spec
- Responsiveness
- Preamble messages
- Task execution
- Validating your work
- Ambition vs. precision
- Sharing progress updates
- Presenting your work and final message
- Final answer structure and style guidelines
- Tool Guidelines
- Shell commands
- Memory Writing Agent: Phase 2 (Consolidation)
- Task 1: <task description, outcome>
- rollout_summary_files
- keywords
- Task 2: <task description, outcome>
- User preferences
- Reusable knowledge

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
