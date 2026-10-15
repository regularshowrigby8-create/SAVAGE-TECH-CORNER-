# Factory stage: nvidia-skillspector

Source factory: NVIDIA/SkillSpector (https://github.com/NVIDIA/SkillSpector), licence Apache-2.0,
commit 240421219409dc0bafef9f618a10d80c2d7e83a3.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Static Analysis
- 2: LLM Semantic Analysis (Optional)

## Checks this factory defines

- what was suppressed (still excluded from the score).
- Workflow

## Prompt assets this factory ships

- skills/skill-inspector/SKILL.md

## What its own definition files describe

- SkillSpector
- Overview
- Documentation
- Features
- Quick Start
- Installation
- Update later: uv tool update skillspector
- Clone the repository
- Create and activate virtual environment
- or: python3 -m venv .venv && source .venv/bin/activate
- Install for production use
- Or install with development dependencies
- Docker (no Python required)
- or: docker build -t skillspector .
- Basic Usage
- Scan a local skill directory
- Scan a single SKILL.md file
- Scan a Git repository
- Scan a zip file
- Size limits
- Output Formats
- Terminal output (default) - pretty formatted
- JSON output - machine readable
- Markdown output - for documentation

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
