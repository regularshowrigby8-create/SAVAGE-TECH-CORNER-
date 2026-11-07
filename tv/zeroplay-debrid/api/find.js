'use strict';

const { findCandidates, normalizeIdentity } = require('../lib/server-finder/core');

const WINDOW_MS = 60 * 1000;
const MAX_REQUESTS_PER_WINDOW = 8;
const requests = new Map();

function setHeaders(res) {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Cache-Control', 'private, max-age=20, stale-while-revalidate=30');
  res.setHeader('Vary', 'Origin');
}

function sameOrigin(req) {
  const origin = req.headers && req.headers.origin;
  if (!origin) return true;
  const host = req.headers['x-forwarded-host'] || req.headers.host;
  try { return !!host && new URL(origin).host.toLowerCase() === String(host).split(',')[0].trim().toLowerCase(); }
  catch { return false; }
}

function clientKey(req) {
  const forwarded = req.headers && req.headers['x-forwarded-for'];
  const ip = forwarded ? String(forwarded).split(',')[0].trim() : String(req.socket && req.socket.remoteAddress || 'unknown');
  return ip.slice(0, 80);
}

function allowRequest(req, now = Date.now()) {
  const key = clientKey(req);
  const prior = requests.get(key);
  if (!prior || now - prior.start >= WINDOW_MS) {
    requests.set(key, { start: now, count: 1 });
    return true;
  }
  if (prior.count >= MAX_REQUESTS_PER_WINDOW) return false;
  prior.count++;
  return true;
}

function inputFor(req) {
  if (req.method === 'GET') {
    const query = req.query || {};
    const title = query.q || query.title || '';
    return {
      title, titles: String(query.titles || '').split('|'), type: query.type,
      year: query.year, season: query.season, episode: query.episode,
      imdbId: query.imdbId, tmdbId: query.tmdbId,
    };
  }
  if (req.body && typeof req.body === 'object') return req.body.identity || req.body;
  if (typeof req.body === 'string') {
    try { const body = JSON.parse(req.body); return body.identity || body; } catch { return null; }
  }
  return null;
}

module.exports = async function handler(req, res) {
  setHeaders(res);
  if (!['GET', 'POST'].includes(req.method)) {
    res.setHeader('Allow', 'GET, POST');
    return res.status(405).json({ ok: false, error: 'Method not allowed' });
  }
  if (!sameOrigin(req)) return res.status(403).json({ ok: false, error: 'Same-origin requests only' });
  if (!allowRequest(req)) {
    res.setHeader('Retry-After', '60');
    return res.status(429).json({ ok: false, error: 'Server Finder is rate limited; please retry shortly.' });
  }
  let identity;
  try { identity = normalizeIdentity(inputFor(req)); }
  catch (error) { return res.status(400).json({ ok: false, error: error.message || 'Invalid title identity' }); }
  const result = await findCandidates(identity, {
    limit: 24, maxPerSource: 8, timeoutMs: 4500,
    sourceConcurrency: 6, useWorkerThreads: false,
  });
  return res.status(200).json(result);
};

module.exports._test = { sameOrigin, allowRequest, inputFor, requests, WINDOW_MS, MAX_REQUESTS_PER_WINDOW };
