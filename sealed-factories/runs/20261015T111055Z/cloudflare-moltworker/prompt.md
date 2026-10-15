# Factory stage: cloudflare-moltworker

Source factory: cloudflare/moltworker (https://github.com/cloudflare/moltworker), licence Apache-2.0,
commit 7b00c1d7292190f2327e93bdcc7a0eabb0a26123.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- Instructions

## Checks this factory defines

- secrets

## Prompt assets this factory ships

- skills/cloudflare-browser/SKILL.md
- skills/cloudflare-browser/scripts/cdp-client.js
- skills/cloudflare-browser/scripts/screenshot.js
- skills/cloudflare-browser/scripts/video.js

## What its own definition files describe

- Project Overview
- Project Structure
- Key Patterns
- Environment Variables
- CLI Commands
- Success Detection
- Commands
- Testing
- Code Style
- Documentation
- Architecture
- Key Files
- Local Development
- Edit .dev.vars with your ANTHROPIC_API_KEY
- WebSocket Limitations
- Docker Image Caching
- Build cache bust: 2026-02-06-v28-openclaw-upgrade
- Gateway Configuration
- AI Provider Priority
- Container Environment Variables
- OpenClaw Config Schema
- Common Tasks
- Adding a New API Endpoint
- Adding a New Environment Variable

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
