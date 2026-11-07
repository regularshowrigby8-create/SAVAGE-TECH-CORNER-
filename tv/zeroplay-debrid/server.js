'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { URL } = require('node:url');
const { findCandidates, normalizeIdentity } = require('./lib/server-finder/core');
const findApi = require('./api/find');

const APP_ROOT = __dirname;
const MAX_BODY_BYTES = 16 * 1024;
const RATE_WINDOW_MS = 60 * 1000;
const RATE_LIMIT = 8;
const requestBuckets = new Map();
const API_HANDLERS = {
  '/api/global-search': require('./api/global-search'),
  '/api/extract': require('./api/extract'),
  '/api/tmdb': require('./api/tmdb'),
};

function jsonResponse(res, status, value, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'X-Content-Type-Options': 'nosniff', ...headers });
  res.end(JSON.stringify(value));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', chunk => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) { reject(new Error('Request body too large')); req.destroy(); return; }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve(null);
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch { reject(new Error('Invalid JSON body')); }
    });
    req.on('error', reject);
  });
}

function sameOrigin(req) {
  const origin = req.headers.origin;
  if (!origin) return true;
  try { return new URL(origin).host.toLowerCase() === String(req.headers.host || '').toLowerCase(); }
  catch { return false; }
}

function allowRequest(req, now = Date.now()) {
  const key = String(req.socket && req.socket.remoteAddress || 'unknown');
  const bucket = requestBuckets.get(key);
  if (!bucket || now - bucket.start >= RATE_WINDOW_MS) {
    requestBuckets.set(key, { start: now, count: 1 });
    return true;
  }
  if (bucket.count >= RATE_LIMIT) return false;
  bucket.count++;
  return true;
}

function localApiResponse(res) {
  let statusCode = 200;
  const headers = {};
  return {
    setHeader(name, value) { headers[name] = value; },
    status(code) { statusCode = code; return this; },
    json(value) {
      if (!res.headersSent) res.writeHead(statusCode, headers);
      res.end(JSON.stringify(value));
      return value;
    },
  };
}

async function handleFind(req, res, url) {
  if (!['GET', 'POST'].includes(req.method)) {
    res.setHeader('Allow', 'GET, POST');
    return jsonResponse(res, 405, { ok: false, error: 'Method not allowed' });
  }
  if (!sameOrigin(req)) return jsonResponse(res, 403, { ok: false, error: 'Same-origin requests only' });
  if (!allowRequest(req)) return jsonResponse(res, 429, { ok: false, error: 'Server Finder is rate limited; please retry shortly.' }, { 'Retry-After': '60' });
  let body = null;
  if (req.method === 'POST') {
    try { body = await readBody(req); }
    catch (error) { return jsonResponse(res, error.message === 'Request body too large' ? 413 : 400, { ok: false, error: error.message }); }
  }
  const identityInput = req.method === 'GET' ? {
    title: url.searchParams.get('q') || url.searchParams.get('title'),
    titles: (url.searchParams.get('titles') || '').split('|'), type: url.searchParams.get('type'),
    year: url.searchParams.get('year'), season: url.searchParams.get('season'), episode: url.searchParams.get('episode'),
    imdbId: url.searchParams.get('imdbId'), tmdbId: url.searchParams.get('tmdbId'),
  } : body && (body.identity || body);
  let identity;
  try { identity = normalizeIdentity(identityInput); }
  catch (error) { return jsonResponse(res, 400, { ok: false, error: error.message || 'Invalid title identity' }); }
  const result = await findCandidates(identity, {
    limit: 24, maxPerSource: 8, timeoutMs: 4500, workerTimeoutMs: 30000,
    sourceConcurrency: 8, useWorkerThreads: true,
  });
  return jsonResponse(res, 200, result, { 'Cache-Control': 'private, max-age=20, stale-while-revalidate=30' });
}

async function handleApi(req, res, url, pathname) {
  if (pathname === '/api/health') return jsonResponse(res, 200, { ok: true, service: 'ZeroPlay Server Finder', mode: 'local-worker-threads', sources: 3, maxCandidatePool: 1000, workerThreads: true });
  if (pathname === '/api/find') return handleFind(req, res, url);
  const handler = API_HANDLERS[pathname];
  if (!handler) return jsonResponse(res, 404, { ok: false, error: 'Not found' });
  let body = null;
  if (req.method === 'POST') {
    try { body = await readBody(req); }
    catch (error) { return jsonResponse(res, 400, { ok: false, error: error.message }); }
  }
  const query = Object.fromEntries(url.searchParams.entries());
  const adaptedReq = Object.assign(req, { query, body });
  return handler(adaptedReq, localApiResponse(res));
}

function appHtml() {
  for (const file of [path.join(APP_ROOT, 'index.html'), path.join(APP_ROOT, 'dist/index.html')]) {
    try { return fs.readFileSync(file); } catch {}
  }
  return Buffer.from('ZeroPlay build is missing. Run npm run build first.');
}

function createLocalServer() {
  const html = appHtml();
  return http.createServer(async (req, res) => {
    let url;
    try { url = new URL(req.url || '/', 'http://' + (req.headers.host || 'localhost')); }
    catch { return jsonResponse(res, 400, { ok: false, error: 'Invalid request URL' }); }
    if (url.pathname.startsWith('/api/')) {
      try { await handleApi(req, res, url, url.pathname); }
      catch { if (!res.headersSent) jsonResponse(res, 502, { ok: false, error: 'Server Finder request failed' }); else res.end(); }
      return;
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { Allow: 'GET, HEAD' });
      return res.end();
    }
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8', 'Content-Length': html.length,
      'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'strict-origin-when-cross-origin',
      'Content-Security-Policy': "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' https: data: blob:; connect-src 'self' https:; frame-src https:; media-src https: blob:; object-src 'none'; base-uri 'self'; form-action 'self'",
    });
    if (req.method === 'HEAD') return res.end();
    return res.end(html);
  });
}

if (require.main === module) {
  const port = Math.max(1, Math.min(65535, Number(process.env.PORT) || 8787));
  const host = process.env.HOST || '0.0.0.0';
  const server = createLocalServer();
  server.listen(port, host, () => console.log('ZeroPlay Server Finder listening on ' + host + ':' + port));
}

module.exports = { createLocalServer, handleFind, readBody, sameOrigin, allowRequest, MAX_BODY_BYTES, RATE_LIMIT };
