# Factory stage: parthalon025-autonomous-coding-toolkit

Source factory: parthalon025/autonomous-coding-toolkit (https://github.com/parthalon025/autonomous-coding-toolkit), licence MIT,
commit 2445ade59d1076c5c2347086ccc9a5d9048d5ac5.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Read target files
- 2: Grep for Priority 1 blocking patterns
- 3: Grep for Priority 2 quality patterns
- 4: Check tooling config
- 5: Run ShellCheck
- 6: Check scope
- 0: Tool Availability Check
- 1: Repo Detection
- 2: CVE Scanning (per repo)
- 3: Cross-Language CVE Aggregation
- 4: Outdated Package Detection (per repo)
- 5: License Compliance (per repo)
- 6: Report
- 1: Load and Review Plan
- 2: Execute Batch
- 3: Report
- 4: Continue
- 5: Complete Development
- 1: Verify Tests
- 2: Determine Base Branch
- 3: Present Options
- 4: Execute Choice
- 5: Cleanup Worktree
- 1: Extract Features

## Workers this factory defines

- agents/bash-expert.md
- agents/dependency-auditor.md
- A: Superpowers Strategy
- B: Ralph Wiggum Strategy
- A (Superpowers) Results
- B (Ralph Wiggum) Results

## Checks this factory defines

- if `shfmt` config exists (`.editorconfig` or flags)
- After agents return:
- critically - identify any questions or concerns about the plan
- fails repeatedly
- plan critically first
- after EACH task
- after each batch (3 tasks)
- {WHAT_WAS_IMPLEMENTED}
- code quality, architecture, testing
- Checklist
- for missing pieces they claimed to implement

## Prompt assets this factory ships

- scripts/prompts/agent-a-superpowers.md
- scripts/prompts/agent-b-ralph.md
- scripts/prompts/judge-agent.md
- scripts/prompts/planner-agent.md
- skills/autocode/ab-verification.md
- skills/autocode/code-quality-standards.md
- skills/dispatching-parallel-agents/SKILL.md
- skills/executing-plans/SKILL.md
- skills/finishing-a-development-branch/SKILL.md
- skills/receiving-code-review/SKILL.md
- skills/requesting-code-review/SKILL.md
- skills/requesting-code-review/code-reviewer.md
- skills/roadmap/SKILL.md
- skills/subagent-driven-development/code-quality-reviewer-prompt.md
- skills/subagent-driven-development/implementer-prompt.md
- skills/subagent-driven-development/spec-reviewer-prompt.md
- skills/systematic-debugging/condition-based-waiting.md
- skills/systematic-debugging/defense-in-depth.md
- skills/systematic-debugging/root-cause-tracing.md
- skills/using-superpowers/SKILL.md
- skills/verify/SKILL.md
- skills/writing-plans/SKILL.md
- skills/writing-skills/SKILL.md

## What its own definition files describe

- Bash Expert
- Scan Workflow (Audit Mode)
- Output Format
- Generation Mode (Writing New Scripts)
- Hallucination Guard
- Dependency Auditor
- CRITICAL / HIGH CVEs — Fix immediately
- MEDIUM CVEs — Fix this sprint
- Outdated Packages (no known CVE)
- License Compliance Issues
- Workspace Rollup
- Key Rules
- Work Unit
- Context
- MAB Lessons (from prior competing runs)
- Instructions
- Strategy Differentiator
- Judge Agent: MAB Evaluation
- Design Document
- Diff
- Quality Gate Output
- Evaluation Criteria
- Planner Agent: Routing Reference
- How Routing Works

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
