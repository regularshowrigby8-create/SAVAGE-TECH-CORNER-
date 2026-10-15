# Factory stage: mcp-atlassian

Source factory: sooperset/mcp-atlassian (https://github.com/sooperset/mcp-atlassian), licence MIT,
commit 0a5d2427144d72f5aade07d4fabd385421cec401.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Checks this factory defines

- for auth errors and return 401 before calling app
- prefix BEFORE stripping to preserve "Bearer " / "Token " matching

## What its own definition files describe

- MCP Atlassian
- Repository map
- Architecture
- Dev workflow
- Rules
- Code conventions
- Gotchas
- Quick reference
- Running the server
- Git workflow
- Quick Start
- 1. Get Your API Token
- 2. Configure Your IDE
- Autohand Code
- 3. Start Using
- Documentation
- Compatibility
- Key Tools
- Security
- Contributing
- License

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
