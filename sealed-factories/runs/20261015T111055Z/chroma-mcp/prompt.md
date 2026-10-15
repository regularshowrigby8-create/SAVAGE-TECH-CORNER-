# Factory stage: chroma-mcp

Source factory: chroma-core/chroma-mcp (https://github.com/chroma-core/chroma-mcp), licence Apache-2.0,
commit 98ff67589bdcc31b730a5415ff9529433f949077.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Checks this factory defines

- if there are empty strings in the ids list
- for duplicate IDs
- the return value

## What its own definition files describe

- Chroma MCP Server
- Features
- Supported Tools
- Embedding Functions
- Usage with Claude Desktop
- Demos
- Using Environment Variables
- Common variables
- For persistent client
- For cloud client (Chroma Cloud)
- For HTTP client (self-hosted)
- Optional: Specify path to .env file (defaults to .chroma_env)
- Embedding Function Environment Variables

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
