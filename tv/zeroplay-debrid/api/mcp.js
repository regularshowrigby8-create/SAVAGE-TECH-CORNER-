'use strict';

const crypto = require('node:crypto');
const { McpServer } = require('@modelcontextprotocol/sdk/server/mcp.js');
const { StreamableHTTPServerTransport } = require('@modelcontextprotocol/sdk/server/streamableHttp.js');
const { z } = require('zod');
const { findCandidates, normalizeIdentity, SOURCE_IDS, MAX_CANDIDATES, MAX_PER_SOURCE } = require('../lib/server-finder/core');

const WINDOW_MS = 60 * 1000;
const MAX_REQUESTS_PER_WINDOW = 8;
const requests = new Map();

function clientKey(req) {
  const forwarded = req.headers && req.headers['x-forwarded-for'];
  return (forwarded ? String(forwarded).split(',')[0].trim() : String(req.socket && req.socket.remoteAddress || 'unknown')).slice(0, 80);
}

function allowRequest(req, now = Date.now()) {
  const key = clientKey(req);
  const previous = requests.get(key);
  if (!previous || now - previous.start >= WINDOW_MS) {
    requests.set(key, { start: now, count: 1 });
    return true;
  }
  if (previous.count >= MAX_REQUESTS_PER_WINDOW) return false;
  previous.count++;
  return true;
}

function sameOriginOrNoOrigin(req) {
  const origin = req.headers && req.headers.origin;
  if (!origin) return true;
  try {
    const host = req.headers['x-forwarded-host'] || req.headers.host;
    return !!host && new URL(origin).host.toLowerCase() === String(host).split(',')[0].trim().toLowerCase();
  } catch { return false; }
}

function tokenMatches(req, expected) {
  if (!expected) return true;
  const header = String(req.headers && req.headers.authorization || '');
  const supplied = header.match(/^Bearer\s+(.+)$/i)?.[1] || '';
  const a = Buffer.from(supplied);
  const b = Buffer.from(expected);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function jsonError(res, status, message, headers = {}) {
  res.statusCode = status;
  for (const [key, value] of Object.entries(headers)) res.setHeader(key, value);
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.end(JSON.stringify({ error: message }));
}

function createMcpServer(finder = findCandidates) {
  const server = new McpServer({ name: 'zeroplay-server-finder', version: '1.0.0' }, { capabilities: { tools: {} } });

  server.registerTool('finder_health', {
    title: 'Server Finder health',
    description: 'Return the hosted ZeroPlay MCP finder status and its actual bounded discovery limits.',
    inputSchema: {},
  }, async () => {
    const status = {
      ok: true,
      service: 'ZeroPlay Server Finder MCP',
      transport: 'streamable-http-stateless',
      execution: process.env.VERCEL ? 'vercel-hosted-bounded-async-pool' : 'local-sidecar-bounded-async-pool',
      sources: SOURCE_IDS.length,
      maxCandidatesPerRequest: Math.min(24, MAX_CANDIDATES),
      maxManifestsPerSource: MAX_PER_SOURCE,
      registeredCandidatePoolCap: MAX_CANDIDATES,
      activeOsWorkerThreads: 0,
      note: 'Discovery runs on the hosted server; no crawler worker threads are started on the client device.',
    };
    return { content: [{ type: 'text', text: JSON.stringify(status) }], structuredContent: status };
  });

  server.registerTool('find_title_servers', {
    title: 'Find title-specific streaming server candidates',
    description: 'Search reviewed public discovery sources for server manifests related to one exact movie or series identity. Returns uninstalled candidates only; the app must still verify the exact title and episode before playback or installation.',
    inputSchema: {
      title: z.string().min(2).max(160).describe('Canonical movie or series title'),
      titles: z.array(z.string().min(2).max(160)).max(4).optional().describe('Original or alternate title variants'),
      type: z.enum(['movie', 'series', 'tv']).optional().default('movie'),
      year: z.number().int().min(1888).max(2200).optional(),
      imdbId: z.string().regex(/^tt\d{5,12}$/i).optional(),
      tmdbId: z.number().int().positive().max(9999999999).optional(),
      season: z.number().int().min(1).max(999).optional(),
      episode: z.number().int().min(1).max(9999).optional(),
    },
  }, async (args) => {
    let identity;
    try { identity = normalizeIdentity(args); }
    catch (error) {
      return { isError: true, content: [{ type: 'text', text: error.message || 'Invalid title identity' }] };
    }
    const result = await finder(identity, {
      limit: 24,
      maxPerSource: 8,
      timeoutMs: 4500,
      sourceConcurrency: 6,
      useWorkerThreads: false,
    });
    return {
      content: [{ type: 'text', text: JSON.stringify(result) }],
      structuredContent: result,
      isError: !result.ok,
    };
  });

  return server;
}

async function handleMcpRequest(req, res, options = {}) {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Cache-Control', 'no-store');
  if (!['POST', 'GET', 'DELETE'].includes(req.method)) {
    res.setHeader('Allow', 'POST, GET, DELETE');
    return jsonError(res, 405, 'MCP Streamable HTTP accepts POST, GET, and DELETE.');
  }
  if (!sameOriginOrNoOrigin(req)) return jsonError(res, 403, 'Cross-origin MCP requests are not allowed.');
  const expectedToken = options.token ?? process.env.ZERO_PLAY_MCP_TOKEN ?? '';
  if (!tokenMatches(req, expectedToken)) return jsonError(res, 401, 'A valid MCP bearer token is required.', { 'WWW-Authenticate': 'Bearer' });
  if (!allowRequest(req)) return jsonError(res, 429, 'MCP request limit reached; retry in one minute.', { 'Retry-After': '60' });

  const allowedHost = process.env.VERCEL_URL || process.env.MCP_ALLOWED_HOST || '';
  const transport = new StreamableHTTPServerTransport({
    sessionIdGenerator: undefined,
    enableJsonResponse: true,
    ...(allowedHost ? { allowedHosts: [allowedHost], enableDnsRebindingProtection: true } : {}),
  });
  const server = createMcpServer(options.finder || findCandidates);
  try {
    await server.connect(transport);
    await transport.handleRequest(req, res, req.body);
  } catch (error) {
    if (!res.headersSent) return jsonError(res, 500, 'MCP request failed.');
    res.end();
  } finally {
    await transport.close().catch(() => {});
    await server.close().catch(() => {});
  }
}

module.exports = async function mcpHandler(req, res) {
  return handleMcpRequest(req, res);
};

module.exports._test = {
  allowRequest, sameOriginOrNoOrigin, tokenMatches, createMcpServer, handleMcpRequest,
  requests, WINDOW_MS, MAX_REQUESTS_PER_WINDOW,
};
