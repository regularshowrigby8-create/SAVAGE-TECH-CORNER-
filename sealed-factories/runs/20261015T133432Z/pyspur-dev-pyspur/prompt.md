# Factory stage: pyspur-dev-pyspur

Source factory: PySpur-Dev/pyspur (https://github.com/PySpur-Dev/pyspur), licence Apache-2.0,
commit 97b9856e49f227a52868b73493c23c43fb4de358.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Define Test Cases
- 2: Build the agent in Python code or via UI
- 3: Iterate obsessively
- 4: Deploy
- 1) Create Document Collection (Chunking + Parsing)
- 2) Create Vector Index (Embedding + Vector DB Upsert)

## Workers this factory defines

- frontend/src/components/nodes/agent/AgentNode.tsx

## Checks this factory defines

- if the parent directory contains a file named "[id].html"
- if PROJECT_ROOT is already defined in .env
- if the response is a tool call

## What its own definition files describe

- 🕸️ Why PySpur?
- Problem: It takes a 1,000 tiny paper cuts to make AI reliable
- Solution: A playground for agents that saves time
- ✨ Core features:
- ⚡ Quick start
- 😎 Feature Reel
- Human-in-the-loop breakpoints:
- Debug at Node Level:
- Multimodal (Upload files or paste URLs)
- Loops
- RAG
- Modular Building Blocks
- Evaluate Final Performance
- Coming soon: Self-improvement
- 🛠️ PySpur Development Setup
- ⭐ Support us

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
