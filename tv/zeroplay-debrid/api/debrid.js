/* ZeroPlay RD — Real-Debrid token validation (Vercel Function)
 * Vercel twin of netlify/functions/debrid.js. Mirrors DebridStream's
 * POST /api/validate-token response: { ok, user: { username, type, expiration } }
 * The token is forwarded to api.real-debrid.com over HTTPS and is
 * never logged, stored, or echoed back.
 */
'use strict';

function send(res, status, body) {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Cache-Control', 'no-store');
  return res.status(status).json(body);
}

module.exports = async function handler(req, res) {
  if (req.method !== 'POST') return send(res, 405, { ok: false, error: 'Method not allowed' });

  let token = '';
  try {
    const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
    token = String(body.token || '').trim();
  } catch { /* fallthrough */ }
  if (!token || token.length > 256) return send(res, 400, { ok: false, error: 'Missing Real-Debrid API token' });

  try {
    const r = await fetch('https://api.real-debrid.com/rest/1.0/user', {
      signal: AbortSignal.timeout(10000),
      headers: { Authorization: 'Bearer ' + token, Accept: 'application/json' },
    });
    if (r.status === 401 || r.status === 403) return send(res, 401, { ok: false, error: 'Invalid API token' });
    if (r.status === 429) return send(res, 429, { ok: false, error: 'Real-Debrid rate limit — try again in a minute' });
    if (!r.ok) return send(res, 502, { ok: false, error: 'Real-Debrid validation failed' });
    const u = await r.json();
    return send(res, 200, { ok: true, user: { username: u.username, type: u.type, expiration: u.expiration } });
  } catch {
    return send(res, 502, { ok: false, error: 'Could not reach Real-Debrid' });
  }
};
