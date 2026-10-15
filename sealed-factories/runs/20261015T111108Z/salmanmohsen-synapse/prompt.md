# Factory stage: salmanmohsen-synapse

Source factory: SalmanMohsen/Synapse (https://github.com/SalmanMohsen/Synapse), licence MIT,
commit 6dbb38d84e1b929e968d8ba3a7fa036d5d3ffcc4.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 18: Soft failure escalation (no retries)
- 1: pre-flight scope/actionability gate (AgentRunStep #0). Runs
- 2: retrieval -- single shared Qdrant collection, project_id
- 3: draft -> self-critique (each internal step self-commits its
- 4: file-grounding validation -- hard-fail and escalate on

## Workers this factory defines

- Runs (Planning Agent plan review — approve/reject/edit)
- planning-service/app/agent/planner.py
- planning-service/app/agent/validation.py

## Checks this factory defines

- owner access privileges
- if a guardrail registered an abort since the last attempt
- if this exception represents an already-escalated logical error
- current status of the agent run

## Prompt assets this factory ships

- backend/app/skill/dependencies.py
- backend/app/skill/models.py
- backend/app/skill/repository.py
- backend/app/skill/router.py
- backend/app/skill/schemas.py
- backend/app/skill/service.py
- backend/app/skill/uow.py
- planning-service/app/prompt/assembly.py
- planning-service/app/prompt/templates.py

## What its own definition files describe

- Synapse
- Table of Contents
- How It Works
- System Architecture
- Repository Structure
- Component Reference
- `backend/` — FastAPI Modular Monolith
- `planning-service/` — Planning Agent
- `code-service/` — Code Agent
- `frontend/` — React + TypeScript
- Technology Stack
- Prerequisites
- Getting Started
- 1. Clone the repository
- 2. Create the root .env file (see Environment Variables below)
- 3. Create a frontend-local env file
- 5. Start everything else
- Environment Variables
- Configuring GitHub Identity
- 1. GitHub OAuth App — platform sign-in
- 2. GitHub App — repository automation identity
- Configuring Google Sign-In (Optional)
- Running the Local LLM
- Development Notes

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
