/* ZeroPlay RD — TMDB catalog proxy (Vercel Function)
 * Vercel twin of netlify/functions/tmdb.js (same allowlist validation,
 * ported from ZeroPlay web/lib/catalog.cjs). Set ONE env var in Vercel:
 *   TMDB_API_KEY (v3 key)  or  TMDB_ACCESS_TOKEN (v4 bearer).
 * Without it, returns 503 and the site falls back to demo / pasted-key mode.
 */
'use strict';

function endpoint(path) {
  return typeof path === 'string' && (
    /^\/(trending\/all\/(day|week)|movie\/(popular|top_rated|now_playing|upcoming)|tv\/(popular|airing_today|on_the_air)|search\/(multi|person)|watch\/providers\/(movie|tv)|discover\/(movie|tv))$/.test(path) ||
    /^\/(movie|tv)\/[1-9]\d{0,9}(\/season\/\d{1,4})?$/.test(path) ||
    /^\/person\/[1-9]\d{0,9}$/.test(path) ||
    /^\/collection\/[1-9]\d{0,9}$/.test(path)
  );
}

const SORTS = new Set([
  'popularity.desc', 'vote_average.desc', 'vote_count.desc',
  'primary_release_date.desc', 'primary_release_date.asc',
  'first_air_date.desc', 'first_air_date.asc',
  'revenue.desc', 'title.asc', 'name.asc',
]);

function query(params) {
  if (!params || !endpoint(params.path)) throw Error('Unsupported catalog route');
  const allowed = new Set(['query', 'page', 'append_to_response', 'include_image_language', 'watch_region',
    'with_watch_providers', 'with_watch_monetization_types', 'sort_by', 'with_genres', 'with_original_language',
    'primary_release_date.gte', 'primary_release_date.lte', 'first_air_date.gte', 'first_air_date.lte',
    'vote_average.gte', 'vote_count.gte', 'with_runtime.lte', 'with_origin_country']);
  const out = new URLSearchParams({ include_adult: 'false' });
  for (const [name, value] of Object.entries(params)) {
    if (name === 'path') continue;
    if (!allowed.has(name) || typeof value !== 'string' || value.length > 200) throw Error('Unsupported parameter');
    if (name === 'page' && (!/^[1-9]\d{0,2}$/.test(value) || Number(value) > 500)) throw Error('Invalid page');
    if (name === 'append_to_response' && !['credits,videos,recommendations', 'credits,videos,recommendations,external_ids', 'external_ids', 'credits,videos,recommendations,images', 'videos', 'videos,images', 'images', 'combined_credits'].includes(value)) throw Error('Invalid details request');
    if (name === 'include_image_language' && !/^(?:[a-z]{2}|null)(?:,(?:[a-z]{2}|null)){0,4}$/.test(value)) throw Error('Invalid image language');
    if (name === 'watch_region' && !['PH', 'US', 'GB', 'CA', 'AU', 'IN', 'JP', 'ZA'].includes(value)) throw Error('Invalid region');
    if (name === 'with_watch_providers' && !/^[1-9]\d{0,7}$/.test(value)) throw Error('Invalid provider');
    if (name === 'with_watch_monetization_types' && value !== 'flatrate') throw Error('Invalid availability');
    if (name === 'sort_by' && !SORTS.has(value)) throw Error('Invalid sorting');
    if (name === 'with_genres' && !/^\d{1,8}$/.test(value)) throw Error('Invalid genre');
    if (name === 'with_original_language' && !/^[a-z]{2}$/.test(value)) throw Error('Invalid language');
    if (['primary_release_date.gte', 'primary_release_date.lte', 'first_air_date.gte', 'first_air_date.lte'].includes(name) && !/^\d{4}-\d{2}-\d{2}$/.test(value)) throw Error('Invalid date filter');
    if (name === 'vote_average.gte' && (!/^\d(?:\.\d)?$/.test(value) || Number(value) > 10)) throw Error('Invalid rating');
    if (name === 'vote_count.gte' && !/^\d{1,7}$/.test(value)) throw Error('Invalid vote filter');
    if (name === 'with_runtime.lte' && (!/^\d{1,3}$/.test(value) || Number(value) > 600)) throw Error('Invalid runtime');
    if (name === 'with_origin_country' && !/^[A-Z]{2}$/.test(value)) throw Error('Invalid country');
    out.set(name, value);
  }
  return out;
}

function send(res, status, body, extra = {}) {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  for (const [k, v] of Object.entries(extra)) res.setHeader(k, v);
  return res.status(status).json(body);
}

module.exports = async function handler(req, res) {
  if (req.method !== 'GET') {
    res.setHeader('Allow', 'GET');
    return send(res, 405, { error: 'Method not allowed' });
  }

  const raw = {};
  for (const [name, value] of Object.entries(req.query || {})) {
    if (Array.isArray(value)) return send(res, 400, { error: 'Invalid catalog request' });
    raw[name] = String(value);
  }

  let params;
  try { params = query(raw); } catch { return send(res, 400, { error: 'Invalid catalog request' }); }

  const v3 = process.env.TMDB_API_KEY;
  const v4 = process.env.TMDB_ACCESS_TOKEN;
  if (!v3 && !v4) return send(res, 503, { error: 'The catalog is temporarily unavailable. Please try again later.', code: 'TMDB_NOT_CONFIGURED' });

  const path = raw.path;
  const url = new URL('https://api.themoviedb.org/3' + path);
  if (v3) params.set('api_key', v3);
  url.search = params.toString();

  try {
    const response = await fetch(url, {
      signal: AbortSignal.timeout(15000),
      headers: { Accept: 'application/json', ...(v4 && !v3 ? { Authorization: 'Bearer ' + v4 } : {}) },
    });
    if (!response.ok) {
      return send(res, response.status === 429 ? 429 : 502,
        { error: 'The catalog is temporarily unavailable. Please try again later.' },
        response.status === 429 ? { 'Retry-After': '30' } : {});
    }
    const data = await response.json();
    return send(res, 200, data, {
      'Cache-Control': path === '/search/multi' ? 'private, max-age=60' : 'public, max-age=300, s-maxage=300',
    });
  } catch {
    return send(res, 502, { error: 'The catalog is temporarily unavailable. Please try again later.' });
  }
};
