# Factory stage: adk-python

Source factory: google/adk-python (https://github.com/google/adk-python), licence Apache-2.0,
commit 0798b2c94c4f5bf83693de9850e451cb17701306.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Check 'references/weather_info.md' for the current weather.

## Workers this factory defines

- src/google/adk/a2a/agent/interceptors/__init__.py
- src/google/adk/agents/callback_context.py
- src/google/adk/agents/llm/__init__.py
- src/google/adk/agents/llm/task/__init__.py

## Checks this factory defines

- if we need the processor: output_schema + tools + cannot use output

## Prompt assets this factory ships

- .agents/skills/adk-architecture/references/architecture-runner-roles.md
- contributing/samples/environment_and_skills/e2b_env_skill_toolset/skills/calc-skill/SKILL.md
- contributing/samples/environment_and_skills/e2b_env_skill_toolset/skills/text-skill/SKILL.md
- contributing/samples/environment_and_skills/e2b_env_skill_toolset/skills/text-skill/scripts/format.sh
- contributing/samples/environment_and_skills/local_env_skill_toolset/skills/calc-skill/SKILL.md
- contributing/samples/environment_and_skills/local_env_skill_toolset/skills/text-skill/SKILL.md
- contributing/samples/environment_and_skills/local_env_skill_toolset/skills/text-skill/scripts/format.sh
- contributing/samples/environment_and_skills/local_environment_skill/skills/weather-skill/SKILL.md
- contributing/samples/environment_and_skills/local_environment_skill/skills/weather-skill/references/weather_info.md
- contributing/samples/environment_and_skills/skills/__init__.py
- contributing/samples/environment_and_skills/skills/skills/weather-skill/SKILL.md
- contributing/samples/environment_and_skills/skills/skills/weather-skill/references/weather_info.md
- contributing/samples/environment_and_skills/skills_agent/skills/weather-skill/SKILL.md
- contributing/samples/environment_and_skills/skills_agent/skills/weather-skill/references/weather_info.md
- src/google/adk/flows/llm_flows/prompt/__init__.py
- src/google/adk/flows/llm_flows/prompt/_dynamic_instructions.py
- src/google/adk/flows/llm_flows/prompt/_identity.py
- src/google/adk/flows/llm_flows/prompt/_instructions.py
- src/google/adk/flows/llm_flows/prompt/_instructions_utils.py
- src/google/adk/flows/llm_flows/prompt/_schema.py
- src/google/adk/skills/README.md

## What its own definition files describe

- Runner vs NodeRunner vs Workflow
- Copyright 2026 Google LLC
- # Licensed under the Apache License, Version 2.0 (the "License");
- you may not use this file except in compliance with the License.
- You may obtain a copy of the License at
- #     http://www.apache.org/licenses/LICENSE-2.0
- # Unless required by applicable law or agreed to in writing, software
- distributed under the License is distributed on an "AS IS" BASIS,
- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
- See the License for the specific language governing permissions and
- limitations under the License.
- Weather Information
- Keep ReadonlyContext for backward compatibility
- CallbackContext is unified into Context
- ADK Skills
- Overview

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
