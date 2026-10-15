# Factory stage: llama-index

Source factory: run-llama/llama_index (https://github.com/run-llama/llama_index), licence MIT,
commit 5b7b6e323e1da3edd787a2104a89257c14f026d0.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- docs/src/content/docs/framework/module_guides/deploying/agents/_meta.yml
- docs/src/content/docs/framework/understanding/agent/_meta.yml
- llama-index-core/llama_index/core/agent/react/__init__.py
- llama-index-integrations/agent/llama-index-agent-agentmesh/llama_index/__init__.py
- llama-index-integrations/agent/llama-index-agent-agentmesh/llama_index/agent/__init__.py
- llama-index-integrations/agent/llama-index-agent-azure/llama_index/agent/azure_foundry_agent/__init__.py

## Checks this factory defines

- if prompts_dict, module_dict has restricted ":" token

## Prompt assets this factory ships

- docs/api_reference/api_reference/prompts/index.md
- docs/examples/prompts/llama2_qa_citation_events.jsonl
- docs/src/content/docs/framework/module_guides/models/prompts/_meta.yml
- docs/src/content/docs/framework/module_guides/models/prompts/index.md
- docs/src/content/docs/framework/module_guides/models/prompts/usage_pattern.md
- llama-index-core/llama_index/core/prompts/__init__.py
- llama-index-core/llama_index/core/prompts/base.py
- llama-index-core/llama_index/core/prompts/chat_prompts.py
- llama-index-core/llama_index/core/prompts/default_prompt_selectors.py
- llama-index-core/llama_index/core/prompts/default_prompts.py
- llama-index-core/llama_index/core/prompts/display_utils.py
- llama-index-core/llama_index/core/prompts/guidance_utils.py
- llama-index-core/llama_index/core/prompts/mixin.py
- llama-index-core/llama_index/core/prompts/prompt_type.py
- llama-index-core/llama_index/core/prompts/prompt_utils.py
- llama-index-core/llama_index/core/prompts/prompts.py
- llama-index-core/llama_index/core/prompts/rich.py
- llama-index-core/llama_index/core/prompts/system.py
- llama-index-core/llama_index/core/prompts/utils.py

## What its own definition files describe

- Required for namespace package

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
