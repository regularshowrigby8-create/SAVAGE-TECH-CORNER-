# Factory stage: github-gh-aw

Source factory: github/gh-aw (https://github.com/github/gh-aw), licence MIT,
commit 5eb4c298ecba515924d30bd7989113dd79d7d421.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- pkg/workflow/prompts/cache_memory_prompt.md
- pkg/workflow/prompts/cache_memory_prompt_multi.md
- pkg/workflow/prompts/checkouts_no_credentials_warning.md
- pkg/workflow/prompts/github_context_prompt.md
- pkg/workflow/prompts/threat_detection.md

## Workers this factory defines

- .squad/agents/Rai/history.md
- .squad/agents/fact-checker/history.md
- .squad/agents/mcmanus/charter.md
- .squad/agents/ralph/charter.md
- .squad/agents/ralph/history.md
- .squad/agents/scribe/history.md
- .squad/agents/verbal/charter.md
- pkg/cli/testdata/model_routing_golden/claude-awf-selected-messages/agent/awf-routing-outcome.json
- pkg/cli/testdata/model_routing_golden/claude-awf-steering-notices/agent/awf-routing-outcome.json
- pkg/cli/testdata/model_routing_golden/copilot-budget-exhausted-steering/agent/awf-routing-outcome.json
- pkg/cli/testdata/model_routing_golden/copilot-cli-builtin-subagents/agent/awf-routing-outcome.json
- pkg/cli/testdata/model_routing_golden/copilot-routed-gpt-responses/agent/awf-routing-outcome.json
- pkg/cli/testdata/model_routing_golden/copilot-sdk-claude-subagent-completions/agent/awf-routing-outcome.json
- pkg/cli/testdata/model_routing_golden/copilot-subagent-failed-and-alias/agent/awf-routing-outcome.json
- pkg/cli/testdata/model_routing_golden_capture/agent/aw_info.json
- pkg/cli/testdata/model_routing_golden_capture/agent/awf-routing-outcome.json
- pkg/cli/testdata/model_routing_golden_capture/agent/output.log
- Output File

## Prompt assets this factory ships

- .claude/skills/playwright-cli/references/element-attributes.md
- .squad/templates/skills/distributed-mesh/mesh.json.example
- pkg/workflow/prompts/cache_memory_prompt.md
- pkg/workflow/prompts/cache_memory_prompt_multi.md
- pkg/workflow/prompts/checkouts_no_credentials_warning.md
- pkg/workflow/prompts/github_context_prompt.md
- pkg/workflow/prompts/threat_detection.md

## What its own definition files describe

- Inspecting Element Attributes
- Examples
- snapshot shows a button as e7 but doesn't reveal its id or data attributes
- get the element's id
- get all CSS classes
- get a specific attribute
- get a computed style property
- Project Context
- Core Context
- Recent Updates
- Learnings
- McManus — CI/CD & Actions Platform Engineer
- Identity
- What I Own
- Boundaries
- Model
- Ralph — Ralph
- Responsibilities
- Work Style
- Verbal — DevRel / Docs
- Cache Folder Available
- Cache Folders Available
- Threat Detection Analysis
- Workflow Source Context

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
