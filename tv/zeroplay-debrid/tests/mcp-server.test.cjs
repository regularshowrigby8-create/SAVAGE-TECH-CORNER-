'use strict';

const assert = require('node:assert/strict');
const http = require('node:http');
const { Client } = require('@modelcontextprotocol/sdk/client/index.js');
const { StreamableHTTPClientTransport } = require('@modelcontextprotocol/sdk/client/streamableHttp.js');
const { handleMcpRequest, tokenMatches, sameOriginOrNoOrigin } = require('../api/mcp')._test;
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  assert.equal(tokenMatches({ headers: { authorization: 'Bearer abc123' } }, 'abc123'), true, 'MCP bearer credentials are timing-safe checked');
  assert.equal(tokenMatches({ headers: {} }, 'abc123'), false, 'protected MCP requests reject missing bearer credentials');
  assert.equal(sameOriginOrNoOrigin({ headers: {} }), true, 'non-browser MCP clients do not need an Origin header');
  assert.equal(sameOriginOrNoOrigin({ headers: { origin: 'https://evil.example', host: 'mcp.example' } }), false, 'browser MCP requests reject cross-origin callers');

  let finderCalls = 0;
  const finder = async identity => {
    finderCalls++;
    return {
      ok: true, identity, mode: 'hosted-bounded-pool', sourcesTotal: 3, sourcesComplete: 3, sourcesFailed: 0,
      candidateCount: 0, candidates: [], sources: [],
    };
  };
  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    try { req.body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : undefined; }
    catch { res.writeHead(400).end(); return; }
    await handleMcpRequest(req, res, { finder, token: 'test-secret' });
  });
  server.listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  const address = server.address();
  const client = new Client({ name: 'mcp-server-test', version: '1.0.0' });
  const transport = new StreamableHTTPClientTransport(new URL(`http://127.0.0.1:${address.port}/mcp`), {
    requestInit: { headers: { Authorization: 'Bearer test-secret' } },
  });
  try {
    await client.connect(transport);
    const tools = await client.listTools();
    assert.deepEqual(tools.tools.map(tool => tool.name).sort(), ['find_title_servers', 'finder_health'], 'hosted MCP advertises its finder tools');
    const health = await client.callTool({ name: 'finder_health', arguments: {} });
    const healthData = health.structuredContent || JSON.parse(health.content[0].text);
    assert.equal(healthData.transport, 'streamable-http-stateless', 'MCP health identifies the standard stateless transport');
    assert.equal(healthData.activeOsWorkerThreads, 0, 'hosted MCP does not start device-local OS worker threads');
    const found = await client.callTool({ name: 'find_title_servers', arguments: { title: 'My Heart Beats for Lola Volcán', type: 'series', year: 2011, imdbId: 'tt1929755', tmdbId: 35281, season: 1, episode: 1 } });
    const data = found.structuredContent || JSON.parse(found.content[0].text);
    assert.equal(data.ok, true, 'MCP title finder returns structured discovery results');
    assert.equal(data.identity.title, 'My Heart Beats for Lola Volcán', 'MCP passes exact title identity to the finder');
    assert.equal(finderCalls, 1, 'only the explicit find tool starts discovery');
  } finally {
    await client.close().catch(() => {});
    await new Promise(resolve => server.close(resolve));
  }

  const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
  const localServer = fs.readFileSync(path.join(__dirname, '..', 'server.js'), 'utf8');
  assert(html.includes('method:"tools/call"') && html.includes('name:"find_title_servers"'), 'browser finder invokes the hosted MCP tool over same-origin HTTP');
  assert(html.includes('notifications/initialized') && html.includes('MCP-Protocol-Version'), 'browser MCP client completes stateless protocol initialization');
  assert(localServer.includes("pathname === '/api/mcp'") && localServer.includes("require('./api/mcp')"), 'local development sidecar exposes the same MCP route');
  console.log('PASS MCP Streamable HTTP initialization, tool discovery, structured tool calls, bearer auth, and app integration');
})().catch(error => { console.error(error); process.exit(1); });
