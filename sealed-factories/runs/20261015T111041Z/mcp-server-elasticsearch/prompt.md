# Factory stage: mcp-server-elasticsearch

Source factory: elastic/mcp-server-elasticsearch (https://github.com/elastic/mcp-server-elasticsearch), licence Apache-2.0,
commit 9e64b842f22269eb214793e1a4885128dc4a8fd8.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- label: "Triggering pipelines"
- plugins
- diff
- wait
- watch
- config
- label
- command
- agents
- image

## Checks this factory defines

- that the Elasticsearch MCP Server appears in your available MCP servers.
- container status
- container logs

## What its own definition files describe

- Elasticsearch MCP Server
- Use the Elasticsearch MCP Server for AI Agents
- Before you begin
- Deploy the Elasticsearch MCP Server
- Choose a protocol
- Configure the stdio protocol
- Set environment variables for stdio mode
- Run the container in stdio mode
- Configure Claude Desktop
- Configure the streamable-HTTP protocol
- Set environment variables for HTTP mode
- Run the container in HTTP mode
- Configure Claude Desktop with HTTP proxy
- Verify the connection
- Monitor health and status
- Test the health endpoint (HTTP mode)
- Verify Elasticsearch connectivity
- Security and sensitive information
- Credential storage
- Data encryption
- Best practices
- AWS service quotas
- Available tools
- Next steps

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
