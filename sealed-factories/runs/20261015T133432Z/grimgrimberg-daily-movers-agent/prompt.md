# Factory stage: grimgrimberg-daily-movers-agent

Source factory: grimgrimberg/daily-movers-agent (https://github.com/grimgrimberg/daily-movers-agent), licence MIT,
commit a8d927cf899f46dc7d124e2613cec88beabc2d8f.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- daily_movers/pipeline/__init__.py
- daily_movers/pipeline/agent.py
- daily_movers/pipeline/critic.py
- daily_movers/pipeline/heuristics.py
- daily_movers/pipeline/llm.py
- daily_movers/pipeline/orchestrator.py

## Checks this factory defines

- – validates, normalises, and applies guard-rails

## What its own definition files describe

- Daily Movers Agent
- What it does
- Quickstart (2 minutes)
- Architecture
- Multi-market watchlist
- Project layout
- Documentation
- License
- ---------------------------------------------------------------------------
- Typed state that flows through the graph
- LangGraph builder – lazy import so the module loads even if langgraph
- is not installed (heuristic fallback still works).
- Singleton compiled graph (created once per process)
- Public entry-point
- Node 1: RESEARCHER
- Node 2: ANALYST
- Conditional edge: analyst → critic or end
- Node 3: CRITIC
- Conditional edge: critic → recommender, retry, or end
- Node 4: RECOMMENDER
- Helper utilities

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
