# Factory stage: mcp-proxy

Source factory: sparfenyuk/mcp-proxy (https://github.com/sparfenyuk/mcp-proxy), licence MIT,
commit 70dccbd5fc46c3bae3358e4e36fd82e6c8f0e6f4.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## What its own definition files describe

- mcp-proxy
- About
- 1. stdio to SSE/StreamableHTTP
- 1.1 Configuration
- 1.2 Example usage
- 2. SSE to stdio
- 2.1 Configuration
- 2.2 Example usage
- Start the MCP server behind the proxy
- Start the MCP server behind the proxy with a custom port
- (deprecated) mcp-proxy --sse-port=8080 uvx mcp-server-fetch
- Start the MCP server behind the proxy with a custom host and port
- (deprecated) mcp-proxy --sse-host=0.0.0.0 --sse-port=8080 uvx mcp-server-fetch
- Start the MCP server behind the proxy with a custom user agent
- Start multiple named MCP servers behind the proxy
- Start multiple named MCP servers using a configuration file
- Start the MCP server with CORS enabled and custom exposed headers
- Named Servers
- Installation
- Installing via PyPI
- Option 1: With uv (recommended)
- Option 2: With pipx (alternative)
- Installing via Github repository (latest)
- Installing as container

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
