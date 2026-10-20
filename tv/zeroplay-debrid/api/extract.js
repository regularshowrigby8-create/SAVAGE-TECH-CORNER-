/* ZeroPlay RD — website extractor + JSON relay (Vercel Function)
 * mode=catalog : fetch a site page, extract title cards (title, watch URL, poster)
 *                + server names/embed hosts from the first watch page.
 * mode=watch   : fetch a watch page, extract embed iframe + direct media + servers.
 * mode=json    : fetch a JSON URL server-side (CORS fallback for Stremio addons).
 * Safety: http(s) only, private/loopback hosts blocked, 1.5 MB cap, 10 s timeout,
 * HTML/JSON only. No logins, no paywall/DRM bypass — plain public-page parsing.
 */
'use strict';

const MAX_BYTES = 1500000;
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36';

function guardUrl(raw) {
  let u;
  try { u = new URL(String(raw || '')); } catch { return null; }
  if (u.protocol !== 'https:' && u.protocol !== 'http:') return null;
  const h = u.hostname.toLowerCase();
  if (h === 'localhost' || h.endsWith('.local') || h.endsWith('.internal')) return null;
  if (/^(127\.|10\.|192\.168\.|169\.254\.|0\.)/.test(h)) return null;
  if (/^172\.(1[6-9]|2\d|3[01])\./.test(h)) return null;
  if (h === '::1' || h === '[::1]') return null;
  if (!h.includes('.')) return null;
  return u;
}

async function fetchText(url, wantJson) {
  const ctl = new AbortController();
  const to = setTimeout(() => ctl.abort(), 10000);
  try {
    const r = await fetch(url, {
      signal: ctl.signal,
      redirect: 'follow',
      headers: { 'User-Agent': UA, Accept: wantJson ? 'application/json,text/plain,*/*' : 'text/html,application/xhtml+xml,*/*' },
    });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const finalHost = new URL(r.url).hostname.toLowerCase();
    if (/^(127\.|10\.|192\.168\.|169\.254\.)/.test(finalHost) || finalHost === 'localhost') throw new Error('Blocked redirect');
    const buf = Buffer.from(await r.arrayBuffer());
    if (buf.length > MAX_BYTES) return buf.subarray(0, MAX_BYTES).toString('utf8');
    return buf.toString('utf8');
  } finally { clearTimeout(to); }
}

const abs = (href, base) => { try { return new URL(href, base).href; } catch { return ''; } };
const stripTags = s => String(s || '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
const decodeEntities = s => String(s || '').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>');

function pageTitle(html) {
  const m = html.match(/<title[^>]*>([^<]{1,160})<\/title>/i);
  return m ? decodeEntities(stripTags(m[1])).slice(0, 120) : '';
}

function iframeHosts(html) {
  const out = new Set();
  for (const m of html.matchAll(/<iframe[^>]+src\s*=\s*["']([^"']+)["']/gi)) {
    try { out.add(new URL(m[1].startsWith('//') ? 'https:' + m[1] : m[1]).hostname); } catch {}
  }
  return [...out];
}

function serverNames(html) {
  const names = new Set();
  for (const m of html.matchAll(/<(?:a|li|button|div|span|option)[^>]*(?:class|id)\s*=\s*"[^"]*server[^"]*"[^>]*>([^<]{2,32})</gi)) {
    const t = decodeEntities(stripTags(m[1]));
    if (t && !/^(server|servers|choose|select|watch|episode)$/i.test(t)) names.add(t.slice(0, 32));
    if (names.size >= 12) break;
  }
  return [...names];
}

function parseCatalog(html, base) {
  const items = [];
  const seen = new Set();
  const anchorRe = /<a\s[^>]*href\s*=\s*"([^"]+)"[^>]*>([\s\S]{0,900}?)<\/a>/gi;
  for (const m of html.matchAll(anchorRe)) {
    const hrefRaw = m[1], inner = m[2] || '';
    if (!hrefRaw || hrefRaw.startsWith('#') || hrefRaw.startsWith('javascript:')) continue;
    const imgTag = (inner.match(/<img\s[^>]*>/i) || [])[0] || '';
    const attr = (tag, name) => {
      const m = tag.match(new RegExp(name + '\\s*=\\s*"([^"]*)"', 'i')) || tag.match(new RegExp(name + "\\s*=\\s*'([^']*)'", 'i'));
      return m ? m[1] : '';
    };
    let poster = attr(imgTag, 'data-src') || attr(imgTag, 'data-lazy-src') || attr(imgTag, 'src');
    if (/^data:/i.test(poster)) poster = (attr(imgTag, 'srcset').split(' ')[0] || '');
    const alt = attr(imgTag, 'alt');
    const titleAttr = (m[0].match(/<a\s[^>]*title\s*=\s*"([^"]+)"/i) || [])[1] || '';
    const title = decodeEntities(stripTags(titleAttr || alt || inner)).slice(0, 110);
    const url = abs(decodeEntities(hrefRaw), base);
    if (!url || !title || title.length < 2) continue;
    let sameOrigin = false;
    try { sameOrigin = new URL(url).origin === new URL(base).origin; } catch {}
    if (!sameOrigin) continue;
    if (/\/(login|register|signup|filter|search|genre|tag|category|user|profile|dmca|terms|privacy|contact)(\/|\?|$)/i.test(url)) continue;
    const likely = /\/(watch|anime|movie|movies|tv|series|detail|info|play|episode|ep-|show|drama)/i.test(url);
    if (!likely && !poster) continue;
    const dedupeKey = url.replace(/\/ep-\d+([/?#]|$)/, '$1');
    if (seen.has(dedupeKey)) continue;
    seen.add(dedupeKey);
    items.push({ title, url, poster: poster ? abs(decodeEntities(poster), base) : '' });
    if (items.length >= 48) break;
  }
  return items;
}

function parseWatch(html, base) {
  const embeds = [];
  for (const m of html.matchAll(/<iframe[^>]+src\s*=\s*["']([^"']+)["']/gi)) {
    let src = decodeEntities(m[1]);
    if (src.startsWith('//')) src = 'https:' + src;
    src = abs(src, base);
    if (!src || /facebook|twitter|sharethis|googletagmanager|google\.com\/recaptcha|disqus|platform-cdn/i.test(src)) continue;
    embeds.push(src);
  }
  embeds.sort((a, b) => (/embed|player|stream|video|play/i.test(b) ? 1 : 0) - (/embed|player|stream|video|play/i.test(a) ? 1 : 0));
  const directSet = new Set();
  for (const m of html.matchAll(/https?:\\?\/\\?\/[^\s"'<>\\]+?\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>\\]*)?/gi)) {
    directSet.add(m[0].replace(/\\\//g, '/'));
    if (directSet.size >= 5) break;
  }
  return {
    title: pageTitle(html),
    embed: embeds[0] || '',
    embeds: embeds.slice(0, 4),
    direct: [...directSet],
    servers: serverNames(html),
    embedHosts: iframeHosts(html),
  };
}

async function handle(mode, rawUrl) {
  const u = guardUrl(rawUrl);
  if (!u) return { status: 400, body: { ok: false, error: 'Invalid or blocked URL' } };
  try {
    if (mode === 'json') {
      const text = await fetchText(u.href, true);
      let data; try { data = JSON.parse(text); } catch { return { status: 502, body: { ok: false, error: 'Remote did not return JSON' } }; }
      return { status: 200, body: data, raw: true };
    }
    const html = await fetchText(u.href, false);
    if (mode === 'watch') return { status: 200, body: { ok: true, mode, url: u.href, ...parseWatch(html, u.href) } };
    const items = parseCatalog(html, u.href);
    let servers = [], embedHosts = iframeHosts(html);
    if (items.length) {
      try {
        const first = guardUrl(items[0].url);
        if (first) { const w = parseWatch(await fetchText(first.href, false), first.href); servers = w.servers; embedHosts = [...new Set([...embedHosts, ...w.embedHosts])]; }
      } catch {}
    }
    return { status: 200, body: { ok: true, mode: 'catalog', url: u.href, site: pageTitle(html), items, servers, embedHosts } };
  } catch (e) {
    return { status: 502, body: { ok: false, error: 'Fetch failed: ' + (e && e.message || e) } };
  }
}

module.exports = async function handler(req, res) {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'Method not allowed' });
  const mode = String((req.query || {}).mode || 'catalog');
  if (!['catalog', 'watch', 'json'].includes(mode)) return res.status(400).json({ ok: false, error: 'Unknown mode' });
  const out = await handle(mode, String((req.query || {}).url || ''));
  return res.status(out.status).json(out.body);
};
module.exports._parse = { parseCatalog, parseWatch, guardUrl, serverNames };
