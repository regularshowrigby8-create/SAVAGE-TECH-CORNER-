# Factory stage: pydantic-ai

Source factory: pydantic/pydantic-ai (https://github.com/pydantic/pydantic-ai), licence MIT,
commit 17423c0592f3a9beb648c7989a59b7652c45bae4.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- pydantic_ai_slim/pydantic_ai/.agents/skills/migrating-agno-to-pydantic-ai/agents/openai.yaml
- pydantic_ai_slim/pydantic_ai/.agents/skills/migrating-google-adk-to-pydantic-ai/agents/openai.yaml
- pydantic_ai_slim/pydantic_ai/.agents/skills/migrating-mastra-to-pydantic-ai/agents/openai.yaml
- pydantic_ai_slim/pydantic_ai/.agents/skills/migrating-openai-agents-sdk-to-pydantic-ai/agents/openai.yaml
- pydantic_ai_slim/pydantic_ai/.agents/skills/migrating-vercel-ai-sdk-and-eve-to-pydantic-ai/agents/openai.yaml
- src/pydantic_ai_harness/pydantic_ai_harness/.agents/skills/migrating-deep-agents-to-pydantic-ai-harness/agents/openai.yaml

## Prompt assets this factory ships

- pydantic_ai_slim/pydantic_ai/.agents/skills/migrating-agno-to-pydantic-ai/agents/openai.yaml
- pydantic_ai_slim/pydantic_ai/.agents/skills/migrating-google-adk-to-pydantic-ai/agents/openai.yaml
- pydantic_ai_slim/pydantic_ai/.agents/skills/migrating-mastra-to-pydantic-ai/agents/openai.yaml
- pydantic_ai_slim/pydantic_ai/.agents/skills/migrating-openai-agents-sdk-to-pydantic-ai/agents/openai.yaml
- pydantic_ai_slim/pydantic_ai/.agents/skills/migrating-vercel-ai-sdk-and-eve-to-pydantic-ai/agents/openai.yaml
- src/pydantic_ai_harness/pydantic_ai_harness/.agents/skills/migrating-deep-agents-to-pydantic-ai-harness/agents/openai.yaml
- src/pydantic_clai2/pydantic_clai2/ui/prompt/_completion_adapter.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/image_input.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/input_history.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/interrupts.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/live_prompt.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/prompt_buffer.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/prompt_completion.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/prompt_cursor.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/prompt_keys.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/prompt_resize.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/prompt_selection.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/prompt_surface.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/prompt_transcript.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/question_input.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/screen.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/terminal_updates.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/text_clipboard.py
- src/pydantic_clai2/pydantic_clai2/ui/prompt/transcript_view.py

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
