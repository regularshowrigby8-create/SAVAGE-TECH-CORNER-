# Factory stage: semantic-kernel

Source factory: microsoft/semantic-kernel (https://github.com/microsoft/semantic-kernel), licence MIT,
commit 5b4a3269a6803f7832568e66313c31d714a1f009.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Checks this factory defines

- if this variable allows dangerous content
- for primitive types
- for date/time types
- for UUID
- for enums
- for None

## Prompt assets this factory ships

- dotnet/samples/Demos/ModelContextProtocolClientServer/MCPServer/Prompts/PromptDefinition.cs
- dotnet/samples/LearnResources/Plugins/Prompts/chat/config.json
- dotnet/samples/LearnResources/Plugins/Prompts/chat/skprompt.txt
- dotnet/src/IntegrationTests/prompts/GenerateStory.yaml
- dotnet/src/IntegrationTests/prompts/GenerateStoryHandlebars.yaml
- dotnet/src/SemanticKernel.Abstractions/Filters/Prompt/IPromptRenderFilter.cs
- dotnet/src/SemanticKernel.Abstractions/Filters/Prompt/PromptRenderContext.cs
- python/samples/concepts/prompt_templates/azure_chat_gpt_api_handlebars.py
- python/samples/concepts/prompt_templates/azure_chat_gpt_api_jinja2.py
- python/samples/concepts/prompt_templates/configuring_prompts.py
- python/samples/concepts/prompt_templates/handlebars_prompts.py
- python/samples/concepts/prompt_templates/load_yaml_prompt.py
- python/samples/concepts/prompt_templates/template_language.py
- python/samples/learn_resources/plugins/prompts/chat/config.json
- python/samples/learn_resources/plugins/prompts/chat/skprompt.txt
- python/semantic_kernel/filters/prompts/prompt_render_context.py
- python/semantic_kernel/prompt_template/__init__.py
- python/semantic_kernel/prompt_template/const.py
- python/semantic_kernel/prompt_template/handlebars_prompt_template.py
- python/semantic_kernel/prompt_template/input_variable.py
- python/semantic_kernel/prompt_template/jinja2_prompt_template.py
- python/semantic_kernel/prompt_template/prompt_template_base.py
- python/semantic_kernel/prompt_template/utils/__init__.py
- python/semantic_kernel/prompt_template/utils/handlebars_system_helpers.py

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
