# Factory stage: sandgardenhq-sgai

Source factory: sandgardenhq/sgai (https://github.com/sandgardenhq/sgai), licence MIT,
commit 9efbb7b3811e862a22659ed2424e4da834fdf0ba.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Gather Snippet Information
- 2: Validate Snippet
- 3: Save Snippet
- 4: Model Configuration

## Workers this factory defines

- cmd/sgai/skel/.sgai/agent/project-critic-minority-report.md
- cmd/sgai/skel/.sgai/agent/project-critic-sibling-evaluator.md
- cmd/sgai/skel/.sgai/agent/shell-script-developer.md
- cmd/sgai/skel/.sgai/agent/webmaster-reviewer.md

## Checks this factory defines

- command return codes
- Scope
- that all required fields are provided
- for filename conflicts and resolve (append -2, -3, etc.)
- commands already run
- after each independent work unit or batch of non-overlapping units
- before merge or completion
- when stuck
- commands:
- expectations:
- Envelope

## Prompt assets this factory ships

- cmd/sgai/skel/.sgai/skills/condition-based-waiting/SKILL.md
- cmd/sgai/skel/.sgai/skills/defense-in-depth/SKILL.md
- cmd/sgai/skel/.sgai/skills/frontend-design/ABOUT.md
- cmd/sgai/skel/.sgai/skills/how-to-use-code-snippets/SKILL.md
- cmd/sgai/skel/.sgai/skills/incorporate-snippets/SKILL.md
- cmd/sgai/skel/.sgai/skills/markdown-navigation/SKILL.md
- cmd/sgai/skel/.sgai/skills/markdown-navigation/markdown-viewer.sh
- cmd/sgai/skel/.sgai/skills/problem-solving/ABOUT.md
- cmd/sgai/skel/.sgai/skills/problem-solving/collision-zone-thinking/SKILL.md
- cmd/sgai/skel/.sgai/skills/problem-solving/inversion-exercise/SKILL.md
- cmd/sgai/skel/.sgai/skills/problem-solving/meta-pattern-recognition/SKILL.md
- cmd/sgai/skel/.sgai/skills/problem-solving/scale-game/SKILL.md
- cmd/sgai/skel/.sgai/skills/problem-solving/simplification-cascades/SKILL.md
- cmd/sgai/skel/.sgai/skills/product-design/antifragility-framework/ABOUT.md
- cmd/sgai/skel/.sgai/skills/product-design/antifragility-framework/SKILL.md
- cmd/sgai/skel/.sgai/skills/product-design/brainstorming/ABOUT.md
- cmd/sgai/skel/.sgai/skills/product-design/goal-md-composer/REFERENCE.md
- cmd/sgai/skel/.sgai/skills/product-design/goal-md-composer/SKILL.md
- cmd/sgai/skel/.sgai/skills/requesting-code-review/SKILL.md
- cmd/sgai/skel/.sgai/skills/subagent-delegation-protocol/SKILL.md
- cmd/sgai/skel/.sgai/skills/subagent-return-protocol/SKILL.md

## What its own definition files describe

- Project Critic MinorityReport
- Explicit State Updates
- Required Inputs
- Dissent Rules
- Output Format
- Project Critic Sibling Evaluator
- Evaluation Rules
- Shell Script Developer
- Your Expertise
- Guidelines
- Portability
- Argument Handling
- Error Handling
- Best Practices
- Process
- Important
- Subagent Output
- Webmaster Reviewer
- Mandatory Review Contract
- Condition-Based Waiting
- Overview
- When to Use
- Core Pattern
- Quick Patterns

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
