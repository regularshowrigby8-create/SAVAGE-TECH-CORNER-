'use strict';

/*
 * ZeroPlay global-search adapter. The per-title browser scheduler calls this
 * same-origin function once per GitHub-imported CDrake-SE engine; browser
 * workers own the fan-out so this never shares the continuous provider crawler.
 * Upstream CDrake-SE source is vendored under lib/vendor/cdrake-se (MIT).
 */
const Aol = require('../lib/vendor/cdrake-se/Engines/Aol');
const Ask = require('../lib/vendor/cdrake-se/Engines/Ask');
const Bing = require('../lib/vendor/cdrake-se/Engines/Bing');
const Google = require('../lib/vendor/cdrake-se/Engines/Google');
const Qwant = require('../lib/vendor/cdrake-se/Engines/Qwant');
const Wikipedia = require('../lib/vendor/cdrake-se/Engines/Wikipedia');
const Yahoo = require('../lib/vendor/cdrake-se/Engines/Yahoo');
const Yep = require('../lib/vendor/cdrake-se/Engines/Yep');
const Youtube = require('../lib/vendor/cdrake-se/Engines/Youtube');
const { kAxiosOptions } = require('../lib/vendor/cdrake-se/Global');
const axios = require('axios');
const cheerio = require('cheerio');

const REQUEST_TIMEOUT_MS = 4500;
kAxiosOptions.timeout = REQUEST_TIMEOUT_MS;
axios.defaults.timeout = REQUEST_TIMEOUT_MS;
const ENGINE_IDS = Object.freeze(['aol', 'ask', 'bing', 'google', 'qwant', 'duckduckgo', 'wikipedia', 'yahoo', 'yep', 'youtube', 'niche']);
const cleanText = (value, max = 500) => String(value || '').replace(/\s+/g, ' ').trim().slice(0, max);

function externalUrl(raw) {
  try {
    let value = String(raw || '').trim();
    if (/^www\./i.test(value) || /^[a-z0-9.-]+\.[a-z]{2,}(?:\/|$)/i.test(value)) value = 'https://' + value;
    const url = new URL(value);
    if (url.protocol !== 'https:' || !url.hostname || url.username || url.password) return '';
    const host = url.hostname.toLowerCase();
    if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || host.endsWith('.internal')) return '';
    if (/^(127\.|10\.|192\.168\.|169\.254\.|0\.)/.test(host) || /^172\.(1[6-9]|2\d|3[01])\./.test(host)) return '';
    return url.href.slice(0, 1600);
  } catch { return ''; }
}
function normRows(engine, payload) {
  const raw = Array.isArray(payload) ? payload : (payload && Array.isArray(payload.Results) ? payload.Results : []);
  const rows = [];
  for (const item of raw.slice(0, 12)) {
    if (typeof item === 'string') {
      rows.push({ title: cleanText(item, 180), href: '', body: '', engine });
      continue;
    }
    const title = cleanText(item && (item.Title || item.title || item.name), 220);
    const href = externalUrl(item && (item.Link || item.href || item.url || item.Source));
    const body = cleanText(item && (item.Description || item.DescriptionText || item.description || item.body || item.Content), 500);
    const videoId = cleanText(item && item.VideoID, 40);
    const finalHref = href || (engine === 'youtube' && /^[A-Za-z0-9_-]{6,20}$/.test(videoId) ? 'https://www.youtube.com/watch?v=' + videoId : '');
    const image = externalUrl(item && (item.Image || item.image || item.Thumbnail && item.Thumbnail.Source));
    if (!title && !finalHref) continue;
    rows.push({ title, href: finalHref, body, image, engine });
  }
  return rows;
}
async function searchDuckDuckGo(query) {
  const response = await axios.get('https://html.duckduckgo.com/html/', {
    params: { q: query }, timeout: REQUEST_TIMEOUT_MS,
    headers: { ...kAxiosOptions.headers, Accept: 'text/html' },
  });
  const $ = cheerio.load(response.data || '');
  const rows = [];
  $('.result').each((_, element) => {
    const result = $(element);
    const anchor = result.find('.result__a').first();
    let href = String(anchor.attr('href') || '').trim();
    try {
      const parsed = new URL(href, 'https://html.duckduckgo.com');
      if (/(^|\.)duckduckgo\.com$/i.test(parsed.hostname) && parsed.pathname === '/l/') {
        href = parsed.searchParams.get('uddg') || '';
      } else href = parsed.href;
    } catch { href = ''; }
    const safeHref = externalUrl(href);
    const title = cleanText(anchor.text(), 220);
    const body = cleanText(result.find('.result__snippet').text(), 500);
    if (safeHref && title) rows.push({ Title: title, Link: safeHref, Description: body });
  });
  return { Results: rows.slice(0, 12) };
}
function queryForEngine(engine, query, language) {
  const encoded = encodeURIComponent(query);
  switch (engine) {
    case 'aol': return new Aol({ Query: encoded, Page: 1 }).Search();
    case 'ask': return new Ask({ Query: encoded, Page: 1 }).Search();
    case 'bing': return new Bing({ Query: encoded, Page: 1, Language: language }).Search();
    case 'google': return new Google({ Query: encoded, Page: 1, Language: language }).Search();
    case 'qwant': return new Qwant({ Query: encoded }).Images();
    case 'duckduckgo': return searchDuckDuckGo(query);
    case 'wikipedia': return new Wikipedia({ Query: query, Language: language }).Suggest();
    case 'yahoo': return new Yahoo({ Query: encoded, Page: 1, Language: language }).Search();
    case 'yep': return new Yep({ Query: encoded, Page: 1, Language: language }).Search();
    case 'youtube': return new Youtube({ Query: query, Limit: 8, AllowPlaylist: false }).Search();
    default: throw new Error('Unsupported search engine');
  }
}
async function runEngine(engine, query, language) {
  const request = engine === 'niche'
    ? Promise.allSettled([
      queryForEngine('google', query, language),
      queryForEngine('wikipedia', query, language),
    ]).then(results => results.flatMap((result, index) => result.status === 'fulfilled' ? normRows(index === 0 ? 'google' : 'wikipedia', result.value) : []))
    : Promise.resolve().then(() => queryForEngine(engine, query, language)).then(value => normRows(engine, value));
  let timer;
  try {
    const result = await Promise.race([
      request,
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Search timed out')), REQUEST_TIMEOUT_MS + 500); }),
    ]);
    return { engine, ok: true, results: result.slice(0, 12) };
  } catch {
    return { engine, ok: false, results: [] };
  } finally { clearTimeout(timer); }
}

module.exports = async function handler(req, res) {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Cache-Control', 'public, max-age=45, s-maxage=45, stale-while-revalidate=90');
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'Method not allowed' });
  const query = req.query || {};
  const engine = String(query.engine || '').toLowerCase();
  const text = cleanText(query.q, 320);
  const language = /^[a-z]{2}-[A-Z]{2}$/.test(String(query.lang || '')) ? String(query.lang) : 'en-US';
  if (!ENGINE_IDS.includes(engine)) return res.status(400).json({ ok: false, error: 'Unknown engine' });
  if (!text || text.length < 2) return res.status(400).json({ ok: false, error: 'A search phrase is required' });
  const result = await runEngine(engine, text, language);
  return res.status(200).json({ ok: true, ...result });
};

module.exports._test = { ENGINE_IDS, runEngine, normRows, externalUrl, queryForEngine };
