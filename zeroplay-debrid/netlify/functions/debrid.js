/* ZeroPlay RD — Real-Debrid token validation (Netlify Function)
 * Mirrors DebridStream's POST /api/validate-token response shape:
 *   { ok: true, user: { username, type, expiration } }
 * The token is forwarded to api.real-debrid.com over HTTPS and is
 * never logged, stored, or echoed back.
 */
'use strict';

const headers = { 'Content-Type': 'application/json; charset=utf-8', 'X-Content-Type-Options': 'nosniff', 'Cache-Control': 'no-store' };
const respond = (statusCode, body) => ({ statusCode, headers, body: JSON.stringify(body) });

exports.handler = async (event) => {
  if (event.httpMethod !== 'POST') return respond(405, { ok: false, error: 'Method not allowed' });

  let token = '';
  try { token = String(JSON.parse(event.body || '{}').token || '').trim(); } catch { /* fallthrough */ }
  if (!token || token.length > 256) return respond(400, { ok: false, error: 'Missing Real-Debrid API token' });

  try {
    const r = await fetch('https://api.real-debrid.com/rest/1.0/user', {
      signal: AbortSignal.timeout(10000),
      headers: { Authorization: 'Bearer ' + token, Accept: 'application/json' },
    });
    if (r.status === 401 || r.status === 403) return respond(401, { ok: false, error: 'Invalid API token' });
    if (r.status === 429) return respond(429, { ok: false, error: 'Real-Debrid rate limit — try again in a minute' });
    if (!r.ok) return respond(502, { ok: false, error: 'Real-Debrid validation failed' });
    const u = await r.json();
    return respond(200, { ok: true, user: { username: u.username, type: u.type, expiration: u.expiration } });
  } catch {
    return respond(502, { ok: false, error: 'Could not reach Real-Debrid' });
  }
};
