# Factory stage: camel

Source factory: camel-ai/camel (https://github.com/camel-ai/camel), licence Apache-2.0,
commit e5e55256e9446f744bf7309c478b084587dbf985.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- apps/agents/README.md
- camel/personas/__init__.py
- camel/types/agents/__init__.py
- examples/agents/mcp_agent/mcp_servers_config.json

## Prompt assets this factory ships

- .camel/skills/skill-creator/references/workflows.md
- camel/prompts/__init__.py
- camel/prompts/ai_society.py
- camel/prompts/base.py
- camel/prompts/code.py
- camel/prompts/evaluation.py
- camel/prompts/generate_text_embedding_data.py
- camel/prompts/image_craft.py
- camel/prompts/misalignment.py
- camel/prompts/multi_condition_image_craft.py
- camel/prompts/object_recognition.py
- camel/prompts/persona_hub.py
- camel/prompts/prompt_templates.py
- camel/prompts/role_description_prompt_template.py
- camel/prompts/solution_extraction.py
- camel/prompts/task_prompt_template.py
- camel/prompts/translation.py
- camel/prompts/video_description_prompt.py
- examples/toolkits/skill_toolkit_example/.camel/skills/data-analyzer/SKILL.md
- examples/toolkits/skill_toolkit_example/.camel/skills/data-analyzer/scripts/analyze.py
- examples/toolkits/skill_toolkit_example/.camel/skills/report-writer/SKILL.md

## What its own definition files describe

- Workflow Patterns
- Sequential Workflows
- Conditional Workflows
- Agents app showcases Role Playing API as a Gradio web app
- Deploy the app to Huggingface
- ========= Copyright 2023-2026 @ CAMEL-AI.org. All Rights Reserved. =========
- Licensed under the Apache License, Version 2.0 (the "License");
- you may not use this file except in compliance with the License.
- You may obtain a copy of the License at
- #     http://www.apache.org/licenses/LICENSE-2.0
- # Unless required by applicable law or agreed to in writing, software
- distributed under the License is distributed on an "AS IS" BASIS,
- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
- See the License for the specific language governing permissions and
- limitations under the License.
- flake8: noqa :E501
- Data Analyzer
- Workflow
- Output Format
- Data Overview
- Key Statistics
- Insights
- Guidelines
- Report Writer

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
