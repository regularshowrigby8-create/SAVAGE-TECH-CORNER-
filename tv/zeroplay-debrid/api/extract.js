/* ZeroPlay — website title lookup + watch resolver (Vercel Function)
 *
 * mode=profile : inspect a user-added website only to discover how its public
 *                search works. It does not import that site's titles/covers.
 * mode=find    : check whether an existing catalogue title exists on that
 *                website, then resolve its public watch/embed/direct URL.
 * mode=watch   : inspect one known watch page for public embed/direct URLs.
 * mode=json    : fetch a JSON URL server-side (CORS fallback for addons).
 * mode=text    : return a validated M3U/M3U8 playlist without truncation.
 * mode=m3u     : extract M3U/M3U8 links/variants from a page or playlist.
 *
 * Safety: http(s) only, private/loopback hosts blocked, 1.5 MB response cap,
 * bounded search requests and timeouts. This parses public pages only; it
 * does not log in, bypass paywalls, solve CAPTCHAs, or bypass DRM.
 */
'use strict';

const MAX_BYTES = 1500000;
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36';
const SEARCH_PLACEHOLDER = '__ZP_Q__';
const BLOCKED_PATH = /\/(login|register|signup|filter|search|genre|genres|tag|tags|category|categories|type|types|year|years|status|user|profile|dmca|terms|privacy|contact|about|home)(\/|\?|$)/i;

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

function canonicalHost(host) {
  return String(host || '').toLowerCase().replace(/^www\./, '');
}

function sameSite(a, b) {
  try { return canonicalHost(new URL(a).hostname) === canonicalHost(new URL(b).hostname); } catch { return false; }
}

async function fetchDocument(url, wantJson, timeoutMs) {
  const ctl = new AbortController();
  const to = setTimeout(() => ctl.abort(), timeoutMs || 10000);
  try {
    const r = await fetch(url, {
      signal: ctl.signal,
      redirect: 'follow',
      headers: { 'User-Agent': UA, Accept: wantJson ? 'application/json,text/plain,*/*' : 'text/html,application/xhtml+xml,*/*' },
    });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const finalUrl = r.url || url;
    const final = guardUrl(finalUrl);
    if (!final) throw new Error('Blocked redirect');
    let buf;
    if (r.body && typeof r.body.getReader === 'function') {
      const reader = r.body.getReader();
      const chunks = [];
      let total = 0;
      while (total <= MAX_BYTES) {
        const part = await reader.read();
        if (part.done) break;
        if (!part.value) continue;
        const room = MAX_BYTES + 1 - total;
        const slice = part.value.byteLength > room ? part.value.subarray(0, room) : part.value;
        chunks.push(Buffer.from(slice));
        total += slice.byteLength;
        if (part.value.byteLength > room || total > MAX_BYTES) {
          try { await reader.cancel(); } catch {}
          break;
        }
      }
      buf = Buffer.concat(chunks, total);
    } else {
      buf = Buffer.from(await r.arrayBuffer());
    }
    const truncated = buf.length > MAX_BYTES;
    const text = (truncated ? buf.subarray(0, MAX_BYTES) : buf).toString('utf8');
    return { text, finalUrl: final.href, status: r.status, truncated };
  } finally { clearTimeout(to); }
}

async function fetchText(url, wantJson, timeoutMs) {
  return (await fetchDocument(url, wantJson, timeoutMs)).text;
}

const abs = (href, base) => { try { return new URL(href, base).href; } catch { return ''; } };
const stripTags = s => String(s || '').replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
function decodeEntities(s) {
  return String(s || '')
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => { try { return String.fromCodePoint(parseInt(n, 16)); } catch { return ''; } })
    .replace(/&#(\d+);/g, (_, n) => { try { return String.fromCodePoint(Number(n)); } catch { return ''; } })
    .replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&apos;/g, "'")
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>');
}
function attr(tag, name) {
  const m = String(tag || '').match(new RegExp(name + '\\s*=\\s*"([^"]*)"', 'i'))
    || String(tag || '').match(new RegExp(name + "\\s*=\\s*'([^']*)'", 'i'));
  return m ? decodeEntities(m[1]) : '';
}

function pageTitle(html) {
  const m = html.match(/<title[^>]*>([^<]{1,220})<\/title>/i);
  return m ? decodeEntities(stripTags(m[1])).slice(0, 160) : '';
}

function iframeHosts(html) {
  const out = new Set();
  for (const m of html.matchAll(/<iframe\b[^>]*>/gi)) {
    const src = attr(m[0], 'src') || attr(m[0], 'data-src') || attr(m[0], 'data-lazy-src');
    if (!src) continue;
    try { out.add(new URL(src.startsWith('//') ? 'https:' + src : src).hostname); } catch {}
  }
  return [...out];
}

function serverNames(html) {
  const names = new Set();
  for (const m of html.matchAll(/<(a|li|button|div|span|option)\b([^>]*)>([\s\S]{0,300}?)<\/\1>/gi)) {
    const attrs = m[2] || '';
    const tagForAttrs = '<x ' + attrs + '>';
    const declaredServer = attr(tagForAttrs, 'data-server');
    const declared = declaredServer || attr(tagForAttrs, 'data-name');
    const classId = ' ' + ((attr(tagForAttrs, 'class') || '') + ' ' + (attr(tagForAttrs, 'id') || '')).toLowerCase() + ' ';
    const looksLikeServer = /(?:^|[\s_-])(?:server|servers|server[-_](?:item|btn|button|name|link|tab|option)|btn[-_]server|sv[-_]item)(?:$|[\s_-])/i.test(classId);
    const rawText = decodeEntities(stripTags(declared || m[3] || ''));
    const t = rawText.slice(0, 40);
    if (!declaredServer && rawText.length > 40) continue;
    if ((looksLikeServer || declaredServer) && t && !/^(server|servers|choose|select|watch|episode|sub|dub|signup|login|register|forgot|settings?|auto(?:[-_ ]?.*)?)$/i.test(t)) names.add(t);
    if (names.size >= 12) break;
  }
  return [...names];
}

function embedScore(url) {
  let score = 0;
  if (/embed|player|stream|video|play|watch/i.test(url)) score += 30;
  if (/\.(m3u8|mp4|mpd)(\?|$)/i.test(url)) score -= 10;
  if (/vid|stream|play|tube|cloud|hide|wish|tape|moly/i.test(url)) score += 10;
  return score;
}

function parseWatch(html, base) {
  const embeds = [];
  for (const m of html.matchAll(/<iframe\b[^>]*>/gi)) {
    let src = attr(m[0], 'src') || attr(m[0], 'data-src') || attr(m[0], 'data-lazy-src');
    if (!src || /^about:blank$/i.test(src)) continue;
    if (src.startsWith('//')) src = 'https:' + src;
    src = abs(src, base);
    if (!src || /facebook|twitter|sharethis|googletagmanager|google\.com\/recaptcha|disqus|platform-cdn/i.test(src)) continue;
    if (!embeds.includes(src)) embeds.push(src);
  }
  const addEmbedCandidate = raw => {
    let src = decodeEntities(String(raw || '')).replace(/\\\//g, '/').replace(/[),.;]+$/, '');
    if (!src || /^about:blank$/i.test(src)) return;
    if (src.startsWith('//')) src = 'https:' + src;
    src = abs(src, base);
    if (!src || !guardUrl(src)) return;
    if (/facebook|twitter|sharethis|googletagmanager|google\.com\/recaptcha|disqus|platform-cdn/i.test(src)) return;
    if (/\.(?:js|css|png|jpe?g|gif|webp|svg|ico|woff2?)(?:\?|#|$)/i.test(src)) return;
    if (!embeds.includes(src)) embeds.push(src);
  };
  for (const m of html.matchAll(/\bdata-(?:src|url|embed|video|link|player)\s*=\s*["']([^"']+)["']/gi)) {
    const candidate = abs(decodeEntities(m[1] || ''), base);
    if (candidate && (embedScore(candidate) >= 30 || /\.(?:m3u8|mp4|mpd)(?:\?|#|$)/i.test(candidate))) addEmbedCandidate(candidate);
  }
  const normalizedHtml = html.replace(/\\\//g, '/').replace(/\\u0026/gi, '&').replace(/&amp;/g, '&');
  for (const m of normalizedHtml.matchAll(/https?:\/\/[^\s"'<>]+/gi)) {
    const candidate = m[0];
    if (/\/(?:embed|player)(?:\/|\?|#|[-_])/i.test(candidate) || /[?&](?:embed|player|video)=/i.test(candidate)) addEmbedCandidate(candidate);
  }
  embeds.sort((a, b) => embedScore(b) - embedScore(a));

  const directSet = new Set();
  for (const m of html.matchAll(/<(?:video|source)\b[^>]*>/gi)) {
    const src = abs(attr(m[0], 'src'), base);
    if (src && /\.(?:m3u8|mp4|mpd|webm)(?:\?|#|$)/i.test(src)) directSet.add(src);
  }
  for (const m of normalizedHtml.matchAll(/https?:\/\/[^\s"'<>]+?\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>]*)?/gi)) {
    directSet.add(m[0]);
    if (directSet.size >= 5) break;
  }
  const direct = [...directSet].slice(0, 5);
  return {
    title: pageTitle(html),
    embed: embeds[0] || '',
    embeds: embeds.slice(0, 4),
    direct,
    servers: serverNames(html),
    embedHosts: iframeHosts(html),
  };
}

function isM3uText(text) {
  return /^#EXTM3U/i.test(String(text || '').trim());
}

function m3uPlaylistInfo(text, base) {
  const clean = String(text || '');
  const lines = clean.split(/\r?\n/).map(line => line.trim());
  const variants = [];
  let pendingVariant = false;
  let pendingDuration = 0;
  let segmentCount = 0;
  let duration = 0;
  let encrypted = false;
  let live = true;
  for (const line of lines) {
    if (!line) continue;
    if (line.startsWith('#EXT-X-STREAM-INF:')) { pendingVariant = true; continue; }
    if (line.startsWith('#EXTINF:')) { pendingDuration = Number(line.slice(8).split(',')[0]) || 0; continue; }
    if (line.startsWith('#EXT-X-KEY:') && !/METHOD=NONE/i.test(line)) encrypted = true;
    if (line === '#EXT-X-ENDLIST') live = false;
    if (line.startsWith('#')) continue;
    const url = abs(line, base);
    if (!url) continue;
    if (pendingVariant) { variants.push(url); pendingVariant = false; continue; }
    segmentCount += 1;
    duration += pendingDuration;
    pendingDuration = 0;
  }
  if (!segmentCount) live = false;
  return { variants: [...new Set(variants)], segmentCount, duration: Math.round(duration), encrypted, live };
}

function extractM3u(text, base) {
  const source = String(text || '');
  if (isM3uText(source)) {
    const info = m3uPlaylistInfo(source, base);
    const isMaster = info.variants.length > 0;
    const summary = isMaster ? `${info.variants.length} variants` : `${info.segmentCount} segments`;
    const detail = isMaster
      ? 'Master playlist. Choose Download to select a quality variant and assemble its segments.'
      : `${info.duration} sec playlist duration${info.encrypted ? ' · encryption declared' : ' · unencrypted'}${info.live ? ' · live/no end marker' : ''}`;
    return {
      title: 'M3U playlist',
      playlists: [{ url: base, label: isMaster ? 'M3U master playlist' : 'M3U media playlist', summary, detail }]
        .concat(info.variants.filter(url => url !== base).map(url => ({ url, label: 'M3U quality variant', summary: '', detail: '' }))),
      direct: [base],
      embeds: [],
      playlist: info,
    };
  }
  const watch = parseWatch(source, base);
  const found = new Set((watch.direct || []).filter(url => /\.m3u8(?:\?|#|$)/i.test(url)));
  const normalized = decodeEntities(source).replace(/\\\//g, '/').replace(/\\u0026/gi, '&');
  for (const m of normalized.matchAll(/(?:https?:\/\/[^\s"'<>]+|(?:\.\.?\/|\/)[^\s"'<>]+?)\.m3u8(?:\?[^\s"'<>]*)?/gi)) {
    const url = abs(String(m[0] || '').replace(/[),.;]+$/, ''), base);
    if (url && guardUrl(url)) found.add(url);
    if (found.size >= 20) break;
  }
  return {
    title: watch.title || '',
    playlists: [...found].map(url => ({ url, label: 'Extracted M3U link', summary: '', detail: '' })),
    direct: watch.direct || [],
    embeds: watch.embeds || [],
    embed: watch.embed || '',
    servers: watch.servers || [],
  };
}

function normalizeTitle(s) {
  return decodeEntities(String(s || ''))
    .replace(/\s*\(source test\)\s*$/i, '')
    .normalize('NFKD').replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/\b(the|a|an)\b/g, ' ')
    .replace(/[^a-z0-9]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}
function slugTitle(s) { return normalizeTitle(s).replace(/ /g, '-'); }
function stripSeasonSuffix(normalized) {
  return String(normalized || '').replace(/\b(season|series|part|cour)\s+\d+\b/g, ' ').replace(/\s+/g, ' ').trim();
}
function explicitSeason(text) {
  const m = normalizeTitle(text).match(/\b(?:season|series|part|cour)\s+(\d{1,3})\b/);
  return m ? Number(m[1]) : null;
}
function tokenSet(s) { return new Set(normalizeTitle(s).split(' ').filter(Boolean)); }
function explicitEpisodeInfo(text) {
  const raw = String(text || '');
  const normalized = raw.replace(/[_./-]+/g, ' ').toLowerCase();
  let m = normalized.match(/\bs\s*(\d{1,3})\s*e\s*(\d{1,4})\b/);
  if (m) return { season: Number(m[1]), episode: Number(m[2]) };
  m = normalized.match(/\bseason\s*(\d{1,3})\b[^\d]{0,24}\bepisode\s*(\d{1,4})\b/);
  if (m) return { season: Number(m[1]), episode: Number(m[2]) };
  const urlEp = raw.match(/(?:\/(?:ep|episode)[-_/]|[?&](?:ep|episode|episode_id)=|-ep(?:isode)?-)(\d{1,4})\b/i);
  const season = explicitSeason(raw);
  const episode = urlEp ? Number(urlEp[1]) : (normalized.match(/\b(?:episode|ep)\s*(\d{1,4})\b/) || [])[1];
  return { season, episode: episode == null ? null : Number(episode) };
}
function extractCandidateIds(candidate) {
  const text = [candidate && candidate.title, candidate && candidate.slugTitle, candidate && candidate.url, candidate && candidate.id].filter(Boolean).join(' ');
  const imdb = (text.match(/\btt\d{6,}\b/i) || [])[0] || '';
  const tmdb = text.match(/\btmdb(?:[:/_-]|%3a)(\d{2,})\b/i) || text.match(/[?&](?:tmdb|tmdb_id)=(\d{2,})\b/i);
  return {
    imdbId: String((candidate && (candidate.imdbId || candidate.imdb_id)) || imdb).toLowerCase(),
    tmdbId: Number((candidate && (candidate.tmdbId || candidate.moviedb_id)) || (tmdb && tmdb[1]) || 0) || null,
  };
}
function titleScore(candidate, targets, opts) {
  opts = opts || {};
  const candidateTexts = [candidate && candidate.title, candidate && candidate.slugTitle].filter(Boolean);
  const candidateIds = extractCandidateIds(candidate);
  const wantedImdb = String(opts.imdb || opts.imdbId || '').toLowerCase();
  const wantedTmdb = Number(opts.tmdb || opts.tmdbId || 0) || null;
  const epInfo = explicitEpisodeInfo([candidate && candidate.title, candidate && candidate.slugTitle, candidate && candidate.url].filter(Boolean).join(' '));
  const episodeConflict = (opts.type === 'tv' || opts.type === 'series') && ((epInfo.season != null && epInfo.season !== Number(opts.season || 1)) || (epInfo.episode != null && epInfo.episode !== Number(opts.episode || 1)));
  if (wantedImdb && candidateIds.imdbId && wantedImdb !== candidateIds.imdbId) return 0;
  if (wantedTmdb && candidateIds.tmdbId && wantedTmdb !== candidateIds.tmdbId) return 0;
  if (((wantedImdb && candidateIds.imdbId === wantedImdb) || (wantedTmdb && candidateIds.tmdbId === wantedTmdb)) && !episodeConflict) return 100;
  if (episodeConflict) return 0;
  let best = 0;
  for (const rawTarget of targets || []) {
    const target = normalizeTitle(rawTarget);
    if (!target) continue;
    const targetTokens = tokenSet(target);
    for (const rawCandidate of candidateTexts) {
      const cand = normalizeTitle(rawCandidate);
      if (!cand) continue;
      let score = 0;
      if (cand === target) score = 100;
      else if (stripSeasonSuffix(cand) === target || cand === stripSeasonSuffix(target)) score = 95;
      else if (cand.startsWith(target + ' ')) {
        const extra = cand.split(' ').length - target.split(' ').length;
        score = Math.max(68, 88 - extra * 4);
      } else if (target.startsWith(cand + ' ')) score = 82;
      else {
        const candTokens = tokenSet(cand);
        let hit = 0;
        for (const t of targetTokens) if (candTokens.has(t)) hit++;
        const containment = hit / Math.max(1, targetTokens.size);
        const jaccard = hit / Math.max(1, new Set([...targetTokens, ...candTokens]).size);
        score = Math.round(containment * 58 + jaccard * 34);
        if (hit === targetTokens.size && targetTokens.size > 1) score = Math.max(score, 84);
      }
      const season = explicitSeason(rawCandidate);
      if (season != null && Number(opts.season || 1) !== season) score -= 22;
      if (season != null && Number(opts.season || 1) === season) score += 3;
      best = Math.max(best, score);
    }
  }
  const url = String((candidate && candidate.url) || '');
  const years = (String((candidate && candidate.title) || '') + ' ' + url).match(/\b(19\d{2}|20\d{2})\b/g) || [];
  if (opts.year && years.length) best += years.includes(String(opts.year)) ? 4 : -14;
  if (opts.type === 'movie' && /\/(tv|series|season|episode)(\/|-)/i.test(url) && !/\/anime\//i.test(url)) best -= 10;
  if ((opts.type === 'tv' || opts.type === 'series') && /\/movies?(\/|-)/i.test(url) && !/\/anime\//i.test(url)) best -= 10;
  if ((opts.type === 'tv' || opts.type === 'series') && epInfo.episode != null && epInfo.episode === Number(opts.episode || 1)) best += 3;
  return Math.max(0, Math.min(100, Math.round(best)));
}
function identifyTitleCandidate(candidate, targets, opts) {
  opts = opts || {};
  const score = titleScore(candidate, targets, opts);
  const candidateIds = extractCandidateIds(candidate);
  const episodeInfo = explicitEpisodeInfo([candidate && candidate.title, candidate && candidate.slugTitle, candidate && candidate.url].filter(Boolean).join(' '));
  const reasons = [];
  const wantedImdb = String(opts.imdb || '').toLowerCase();
  const wantedTmdb = Number(opts.tmdb || 0) || null;
  let hardReject = false;
  if (wantedImdb && candidateIds.imdbId && wantedImdb !== candidateIds.imdbId) { hardReject = true; reasons.push('different IMDb id'); }
  else if (wantedImdb && candidateIds.imdbId === wantedImdb) reasons.push('exact IMDb id');
  if (wantedTmdb && candidateIds.tmdbId && wantedTmdb !== candidateIds.tmdbId) { hardReject = true; reasons.push('different TMDB id'); }
  else if (wantedTmdb && candidateIds.tmdbId === wantedTmdb) reasons.push('exact TMDB id');
  if ((opts.type === 'tv' || opts.type === 'series') && ((episodeInfo.season != null && episodeInfo.season !== Number(opts.season || 1)) || (episodeInfo.episode != null && episodeInfo.episode !== Number(opts.episode || 1)))) {
    hardReject = true; reasons.push('different season/episode');
  }
  if (score >= 95) reasons.push('exact title');
  else if (score >= 80) reasons.push('verified title');
  else if (score >= 55) reasons.push('partial title');
  if (opts.year) {
    const years = ([candidate && candidate.title, candidate && candidate.url].filter(Boolean).join(' ').match(/\b(19\d{2}|20\d{2})\b/g) || []);
    if (years.length) reasons.push(years.includes(String(opts.year)) ? 'year match' : 'year differs');
  }
  return {
    score: hardReject ? 0 : score,
    verdict: hardReject ? 'rejected' : score >= 95 ? 'exact' : score >= 80 ? 'verified' : score >= 65 ? 'possible' : 'rejected',
    accepted: !hardReject && score >= 80,
    hardReject,
    reasons,
    candidateIds,
    episodeInfo,
    matchedTitle: (candidate && (candidate.title || candidate.slugTitle)) || '',
  };
}

function candidateSlugTitle(url) {
  try {
    const parts = new URL(url).pathname.split('/').filter(Boolean).map(x => { try { return decodeURIComponent(x); } catch { return x; } });
    const useful = parts.filter(p => !/^(anime|movie|movies|tv|series|show|watch|detail|info|play|title|video|episode|ep-\d+|\d+)$/i.test(p));
    return normalizeTitle(useful.join(' ').replace(/[-_]+/g, ' '));
  } catch { return ''; }
}

function parseResultLinks(html, base) {
  const byUrl = new Map();
  const anchorRe = /<a\s[^>]*href\s*=\s*"([^"]+)"[^>]*>([\s\S]{0,1200}?)<\/a>/gi;
  for (const m of html.matchAll(anchorRe)) {
    const hrefRaw = decodeEntities(m[1] || '');
    if (!hrefRaw || hrefRaw.startsWith('#') || /^javascript:/i.test(hrefRaw)) continue;
    let url = abs(hrefRaw, base);
    if (!url || !guardUrl(url) || !sameSite(url, base)) continue;
    try {
      const u = new URL(url);
      u.hash = '';
      if (u.pathname === '/' || BLOCKED_PATH.test(u.pathname + u.search)) continue;
      url = u.href;
    } catch { continue; }
    const inner = m[2] || '';
    const imgTag = (inner.match(/<img\s[^>]*>/i) || [])[0] || '';
    const openTag = m[0].slice(0, m[0].indexOf('>') + 1);
    const title = decodeEntities(stripTags(attr(openTag, 'title') || attr(imgTag, 'alt') || inner)).slice(0, 140);
    const slug = candidateSlugTitle(url);
    if ((!title || title.length < 2) && (!slug || slug.length < 2)) continue;
    const prior = byUrl.get(url);
    if (!prior || (!prior.title && title) || (title && title.length < prior.title.length && normalizeTitle(title).length >= 2)) {
      byUrl.set(url, { title: title || (prior && prior.title) || '', slugTitle: slug, url });
    }
    if (byUrl.size >= 220) break;
  }
  return [...byUrl.values()];
}

function addTemplate(out, template, base) {
  if (!template) return;
  let t = String(template).replace(/%7B/gi, '{').replace(/%7D/gi, '}');
  t = t.replaceAll(SEARCH_PLACEHOLDER, '{q}');
  if (!t.includes('{q}') && !t.includes('{slug}')) return;
  const probe = t.replace('{q}', 'test').replace('{slug}', 'test');
  const u = guardUrl(abs(probe, base));
  if (!u || !sameSite(u.href, base)) return;
  if (!out.includes(t)) out.push(t);
}

function searchTemplatesFromHtml(html, base) {
  const out = [];
  const searchNames = new Set(['q', 'query', 'search', 'keyword', 'keywords', 's', 'title', 'text', 'term', 'name']);
  for (const fm of html.matchAll(/<form\b[^>]*>[\s\S]{0,4000}?<\/form>/gi)) {
    const form = fm[0];
    const open = form.slice(0, form.indexOf('>') + 1);
    if (/method\s*=\s*["']?post/i.test(open)) continue;
    const inputs = [...form.matchAll(/<input\b[^>]*>/gi)].map(x => x[0]);
    const searchInput = inputs.find(tag => {
      const name = attr(tag, 'name').toLowerCase();
      return searchNames.has(name) || /^search$/i.test(attr(tag, 'type')) || /search/i.test(attr(tag, 'placeholder'));
    });
    if (!searchInput) continue;
    const name = attr(searchInput, 'name') || 'q';
    const action = abs(attr(open, 'action') || base, base);
    const u = guardUrl(action);
    if (!u) continue;
    for (const tag of inputs) {
      const n = attr(tag, 'name');
      if (!n || n === name) continue;
      if (/hidden/i.test(attr(tag, 'type'))) u.searchParams.set(n, attr(tag, 'value') || '');
    }
    u.searchParams.set(name, SEARCH_PLACEHOLDER);
    addTemplate(out, u.href, base);
  }
  for (const m of html.matchAll(/(?:href|action)\s*=\s*"([^"]*(?:search|filter)[^"]*)"/gi)) {
    const raw = abs(decodeEntities(m[1]), base);
    const u = guardUrl(raw);
    if (!u) continue;
    for (const key of ['keyword', 'q', 'query', 'search', 's', 'title']) {
      if (u.searchParams.has(key)) { u.searchParams.set(key, SEARCH_PLACEHOLDER); addTemplate(out, u.href, base); break; }
    }
  }
  return out.slice(0, 8);
}

function commonSearchTemplates(siteUrl) {
  const u = guardUrl(siteUrl);
  if (!u) return [];
  const roots = new Set();
  roots.add(u.origin);
  try { roots.add(new URL('./', u.href).href.replace(/\/$/, '')); } catch {}
  const suffixes = ['/search?keyword={q}', '/search?q={q}', '/search?query={q}', '/filter?keyword={q}', '/?s={q}', '/search/{slug}', '/search/{q}'];
  const out = [];
  for (const root of roots) for (const suffix of suffixes) {
    const t = root + suffix;
    if (!out.includes(t)) out.push(t);
  }
  return out;
}

function buildSearchUrl(template, query) {
  return String(template || '')
    .replaceAll('{slug}', slugTitle(query))
    .replaceAll('{q}', encodeURIComponent(query));
}

async function profileSite(rawUrl) {
  const u = guardUrl(rawUrl);
  if (!u) throw new Error('Invalid or blocked URL');
  const docs = [];
  const first = await fetchDocument(u.href, false, 8000);
  docs.push(first);
  let templates = searchTemplatesFromHtml(first.text, first.finalUrl);
  if (!templates.length && !sameSite(first.finalUrl, u.origin + '/')) {
    try {
      const root = await fetchDocument(u.origin + '/', false, 8000);
      docs.push(root);
      templates = searchTemplatesFromHtml(root.text, root.finalUrl);
    } catch {}
  } else if (!templates.length && u.pathname !== '/') {
    try {
      const root = await fetchDocument(u.origin + '/', false, 8000);
      docs.push(root);
      templates = [...new Set([...templates, ...searchTemplatesFromHtml(root.text, root.finalUrl)])];
    } catch {}
  }
  if (!templates.length) templates = commonSearchTemplates(u.href).slice(0, 4);
  const embedHosts = [...new Set(docs.flatMap(d => iframeHosts(d.text)))];
  return {
    ok: true,
    mode: 'profile',
    url: first.finalUrl,
    origin: u.origin,
    lookup: 'dynamic-title-search',
    searchTemplate: templates[0] || '',
    searchTemplates: templates.slice(0, 5),
    embedHosts,
  };
}

function adjustEpisodeInUrl(url, season, episode) {
  let out = String(url || '');
  const ep = episode == null ? null : String(episode);
  const se = season == null ? null : String(season);
  if (ep != null) {
    out = out.replace(/([?&](?:ep|episode|episode_id)=)\d+/i, '$1' + ep);
    out = out.replace(/(\/(?:ep|episode)[-_/])(\d+)(\/?(?:[?#].*)?)$/i, '$1' + ep + '$3');
    out = out.replace(/(-ep(?:isode)?-)(\d+)(\/?(?:[?#].*)?)$/i, '$1' + ep + '$3');
  }
  if (se != null && ep != null) {
    out = out.replace(/(\/season[-_/])(\d+)(\/episode[-_/])(\d+)/i, '$1' + se + '$3' + ep);
    out = out.replace(/(\/)(\d{1,3})\/(\d{1,4})(\/?(?:[?#].*)?)$/i, (m, slash, oldSeason, oldEpisode, tail) => {
      return /\/(tv|series|show|anime)\//i.test(out) ? slash + se + '/' + ep + tail : m;
    });
  }
  return out;
}

function episodeCandidateUrls(url, episode) {
  if (episode == null) return [];
  try {
    const u = new URL(url);
    if (/(?:\/(?:ep|episode)[-_/]\d+|-ep(?:isode)?-\d+|[?&](?:ep|episode|episode_id)=\d+)/i.test(u.href)) return [];
    const path = u.pathname.replace(/\/+$/, '');
    if (!path || path === '/') return [];
    return [u.origin + path + '/ep-' + episode, u.origin + path + '/episode-' + episode];
  } catch { return []; }
}

function findEpisodeUrl(html, base, season, episode) {
  if (episode == null) return '';
  const stripEpisode = value => {
    try {
      const u = new URL(value);
      return u.origin + u.pathname
        .replace(/\/(?:ep|episode)[-_/]\d+(?:\/|$)/i, '/')
        .replace(/-ep(?:isode)?-\d+(?:\/|$)/i, '/')
        .replace(/\/$/, '');
    } catch { return ''; }
  };
  const baseKey = stripEpisode(base);
  let best = '', bestScore = -1;
  for (const m of String(html || '').matchAll(/<a\s[^>]*href\s*=\s*"([^"]+)"[^>]*>([\s\S]{0,500}?)<\/a>/gi)) {
    const url = abs(decodeEntities(m[1] || ''), base);
    if (!url || !guardUrl(url) || !sameSite(url, base)) continue;
    const openTag = m[0].slice(0, m[0].indexOf('>') + 1);
    const text = normalizeTitle(attr(openTag, 'title') || stripTags(m[2] || ''));
    const epMatches = [
      new RegExp('/(?:ep|episode)[-_/]' + episode + '(?:[/?#]|$)', 'i'),
      new RegExp('-ep(?:isode)?-' + episode + '(?:[/?#]|$)', 'i'),
      new RegExp('[?&](?:ep|episode|episode_id)=' + episode + '(?:[&#]|$)', 'i'),
    ].some(re => re.test(url));
    const textMatches = text === String(episode) || text === 'episode ' + episode || text === 'ep ' + episode;
    if (!epMatches && !textMatches) continue;
    let score = epMatches ? 60 : 40;
    if (baseKey && stripEpisode(url) === baseKey) score += 25;
    const seasonInUrl = explicitSeason(url);
    if (seasonInUrl != null) score += seasonInUrl === Number(season || 1) ? 20 : -50;
    if (score > bestScore) { bestScore = score; best = url; }
  }
  return bestScore >= 40 ? best : '';
}
function cleanVariants(query) {
  const values = [query.title, ...(Array.isArray(query.alternatives) ? query.alternatives : []), query.alt].flatMap(v => String(v || '').split('|'));
  const seen = new Set(), out = [];
  for (const v of values) {
    const clean = String(v || '').replace(/\s*\(source test\)\s*$/i, '').trim().slice(0, 140);
    const key = normalizeTitle(clean);
    if (clean && key && !seen.has(key)) { seen.add(key); out.push(clean); }
  }
  return out.slice(0, 12);
}

async function resolveMatchedWatch(matchUrl, query) {
  const isTv = query.type === 'tv' || query.type === 'series';
  const adjusted = isTv ? adjustEpisodeInUrl(matchUrl, query.season || 1, query.episode || 1) : matchUrl;
  const attempts = [...new Set([adjusted, matchUrl].filter(Boolean))];
  let last = null;

  for (const candidateUrl of attempts) {
    const safe = guardUrl(candidateUrl);
    if (!safe) continue;
    try {
      const doc = await fetchDocument(safe.href, false, 8000);
      let watch = parseWatch(doc.text, doc.finalUrl);
      let watchUrl = doc.finalUrl;
      let resolvedEpisode = false;
      if (isTv && !watch.embed && !watch.direct.length) {
        let episodeUrl = findEpisodeUrl(doc.text, doc.finalUrl, query.season || 1, query.episode || 1);
        if (episodeUrl) resolvedEpisode = true;
        if (episodeUrl && episodeUrl !== doc.finalUrl) {
          const epDoc = await fetchDocument(episodeUrl, false, 8000);
          watch = parseWatch(epDoc.text, epDoc.finalUrl);
          watchUrl = episodeUrl;
        } else if (!episodeUrl) {
          for (const inferredUrl of episodeCandidateUrls(doc.finalUrl, query.episode || 1)) {
            try {
              const epDoc = await fetchDocument(inferredUrl, false, 7000);
              const epPattern = new RegExp('(?:/(?:ep|episode)[-_/]' + (query.episode || 1) + '(?:[/?#]|$)|-ep(?:isode)?-' + (query.episode || 1) + '(?:[/?#]|$))', 'i');
              const looksLikeEpisode = epPattern.test(epDoc.finalUrl) || !!findEpisodeUrl(epDoc.text, epDoc.finalUrl, query.season || 1, query.episode || 1);
              if (!looksLikeEpisode) continue;
              watch = parseWatch(epDoc.text, epDoc.finalUrl);
              watchUrl = epPattern.test(epDoc.finalUrl) ? epDoc.finalUrl : inferredUrl;
              episodeUrl = watchUrl;
              resolvedEpisode = true;
              break;
            } catch {}
          }
        }
      }
      last = { watch, watchUrl };
      if (watch.direct.length || watch.embed) return last;
      if (resolvedEpisode) return last;
      if (isTv && candidateUrl === adjusted && adjusted !== matchUrl) return last;
    } catch {}
  }
  return last || { watch: { embed: '', embeds: [], direct: [], servers: [], embedHosts: [] }, watchUrl: adjusted || matchUrl };
}

async function findTitleOnSite(rawSiteUrl, rawQuery) {
  const site = guardUrl(rawSiteUrl);
  if (!site) throw new Error('Invalid or blocked site URL');
  const query = {
    title: String(rawQuery.title || rawQuery.q || '').trim(),
    alternatives: rawQuery.alternatives || [],
    alt: rawQuery.alt || '',
    type: rawQuery.type || 'movie',
    year: rawQuery.year || '',
    season: Number(rawQuery.season || 1) || 1,
    episode: Number(rawQuery.episode || 1) || 1,
    imdb: String(rawQuery.imdb || '').toLowerCase(),
    tmdb: Number(rawQuery.tmdb || rawQuery.tmdbId || 0) || null,
  };
  const variants = cleanVariants(query);
  if (!variants.length) throw new Error('A catalogue title is required');

  let homeDoc = null;
  try { homeDoc = await fetchDocument(site.href, false, 7000); } catch {}
  const templates = [];
  if (rawQuery.searchTemplate) addTemplate(templates, rawQuery.searchTemplate, site.href);
  if (homeDoc) for (const t of searchTemplatesFromHtml(homeDoc.text, homeDoc.finalUrl)) addTemplate(templates, t, site.href);
  for (const t of commonSearchTemplates(site.href)) addTemplate(templates, t, site.href);

  let best = null;
  let checked = 0;
  const consider = (candidates, strategy) => {
    for (const candidate of candidates || []) {
      if (!guardUrl(candidate.url) || !sameSite(candidate.url, site.href)) continue;
      const identity = identifyTitleCandidate(candidate, variants, query);
      const score = identity.score;
      checked++;
      const epPattern = /(?:\/(?:ep|episode)[-_/]\d+|-ep(?:isode)?-\d+|[?&](?:ep|episode|episode_id)=\d+)/i;
      const episodeTie = best && score === best.score && (query.type === 'tv' || query.type === 'series') && epPattern.test(candidate.url) && !epPattern.test(best.url);
      if (!best || score > best.score || episodeTie) best = { score, url: candidate.url, strategy, title: candidate.title || candidate.slugTitle || '', identity };
    }
  };

  if (homeDoc) {
    consider(parseResultLinks(homeDoc.text, homeDoc.finalUrl), 'site-home');
    const homeTitle = pageTitle(homeDoc.text).replace(/\s+[-|–—].*$/, '');
    consider([{ title: homeTitle, slugTitle: candidateSlugTitle(homeDoc.finalUrl), url: homeDoc.finalUrl }], 'pasted-page');
  }

  let searchFetches = 0;
  outer:
  for (let vi = 0; vi < variants.length; vi++) {
    if (best && best.score >= 95) break;
    if (vi > 0 && best && best.score >= 80) break;
    for (const template of templates.slice(0, 8)) {
      if (searchFetches >= 10) break outer;
      const searchUrl = guardUrl(buildSearchUrl(template, variants[vi]));
      if (!searchUrl || !sameSite(searchUrl.href, site.href)) continue;
      searchFetches++;
      try {
        const doc = await fetchDocument(searchUrl.href, false, 6000);
        consider(parseResultLinks(doc.text, doc.finalUrl), 'site-search');
        const finalTitle = pageTitle(doc.text).replace(/\s+[-|–—].*$/, '');
        if (!BLOCKED_PATH.test(new URL(doc.finalUrl).pathname)) {
          consider([{ title: finalTitle, slugTitle: candidateSlugTitle(doc.finalUrl), url: doc.finalUrl }], 'search-redirect');
        }
        if (best && best.score >= 98) break outer;
      } catch {}
    }
  }

  if (!best || best.score < 80) {
    return { ok: true, mode: 'find', matched: false, checked, searchFetches, bestScore: best ? best.score : 0, identity: best ? best.identity : null, bestTitle: best ? best.title : '' };
  }

  const resolved = await resolveMatchedWatch(best.url, query);
  const watch = resolved.watch;
  const playUrl = (watch.direct && watch.direct[0]) || watch.embed || resolved.watchUrl;
  return {
    ok: true,
    mode: 'find',
    matched: true,
    checked,
    searchFetches,
    match: { score: best.score, strategy: best.strategy, title: best.title || '' },
    identity: best.identity || null,
    matchedUrl: best.url,
    watchUrl: resolved.watchUrl,
    watchTitle: watch.title || '',
    playUrl,
    embed: watch.embed || '',
    embeds: watch.embeds || [],
    direct: watch.direct || [],
    servers: watch.servers || [],
    embedHosts: watch.embedHosts || [],
    pageFallback: !watch.embed && !(watch.direct && watch.direct.length),
  };
}

async function handle(mode, rawUrl, query) {
  query = query || {};
  const u = guardUrl(rawUrl);
  if (!u) return { status: 400, body: { ok: false, error: 'Invalid or blocked URL' } };
  try {
    if (mode === 'json') {
      const text = await fetchText(u.href, true, 10000);
      let data; try { data = JSON.parse(text); } catch { return { status: 502, body: { ok: false, error: 'Remote did not return JSON' } }; }
      return { status: 200, body: data, raw: true };
    }
    if (mode === 'profile') return { status: 200, body: await profileSite(u.href) };
    if (mode === 'find') {
      const body = await findTitleOnSite(u.href, {
        title: query.q || query.title || '',
        alt: query.alt || '',
        type: query.type || 'movie',
        year: query.year || '',
        season: query.season || 1,
        episode: query.episode || 1,
        imdb: query.imdb || '',
        tmdb: query.tmdb || '',
        searchTemplate: query.searchTemplate || '',
      });
      return { status: 200, body };
    }
    const doc = await fetchDocument(u.href, false, 10000);
    if (mode === 'text') {
      if (!isM3uText(doc.text)) return { status: 415, body: { ok: false, error: 'Remote did not return an M3U/M3U8 playlist' } };
      if (doc.truncated) return { status: 413, body: { ok: false, error: 'Remote playlist is larger than the resolver safety limit' } };
      return { status: 200, body: { ok: true, mode: 'text', url: doc.finalUrl, text: doc.text } };
    }
    if (mode === 'm3u') {
      return { status: 200, body: { ok: true, mode: 'm3u', url: doc.finalUrl, truncated: !!doc.truncated, ...extractM3u(doc.text, doc.finalUrl) } };
    }
    return { status: 200, body: { ok: true, mode: 'watch', url: doc.finalUrl, truncated: !!doc.truncated, ...parseWatch(doc.text, doc.finalUrl) } };
  } catch (e) {
    return { status: 502, body: { ok: false, error: 'Site lookup failed: ' + (e && e.message || e) } };
  }
}

module.exports = async function handler(req, res) {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'GET') return res.status(405).json({ ok: false, error: 'Method not allowed' });
  const query = req.query || {};
  const mode = String(query.mode || 'profile');
  if (!['profile', 'find', 'watch', 'json', 'text', 'm3u'].includes(mode)) return res.status(400).json({ ok: false, error: 'Unknown mode' });
  const out = await handle(mode, String(query.url || ''), query);
  return res.status(out.status).json(out.body);
};
module.exports._parse = { parseWatch, extractM3u, isM3uText, parseResultLinks, guardUrl, serverNames, normalizeTitle, titleScore, identifyTitleCandidate, explicitEpisodeInfo, extractCandidateIds, searchTemplatesFromHtml, commonSearchTemplates, buildSearchUrl, adjustEpisodeInUrl, findEpisodeUrl, episodeCandidateUrls };
module.exports._find = { profileSite, findTitleOnSite, handle };
