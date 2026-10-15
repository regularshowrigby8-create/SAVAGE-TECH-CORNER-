# Factory stage: notion-mcp-server

Source factory: makenotion/notion-mcp-server (https://github.com/makenotion/notion-mcp-server), licence MIT,
commit 730ae781ba28beeaf0865025a3f2ed4c25ea2387.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## What its own definition files describe

- Notion MCP Server
- ⚠️ Version 2.0.0 breaking changes
- What changed
- Do I need to migrate?
- Page content as Markdown
- Installation
- 1. Setting up integration in Notion
- 2. Connecting content to integration
- 3. Adding MCP config to your client
- Transport options
- STDIO transport (default)
- Run with default stdio transport
- Or explicitly specify stdio
- Streamable HTTP transport
- Run with Streamable HTTP transport on port 3000 (default)
- Run on a custom port
- Bind to a different host. The default is 127.0.0.1.
- Run with a custom authentication token
- Example request
- Enable per-request Notion tokens (flag or ENABLE_TOKEN_PASSTHROUGH=true)
- Examples
- Development
- Build & test
- Execute

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
