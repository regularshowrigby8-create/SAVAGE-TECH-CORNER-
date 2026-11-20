"use strict";
/**
 * Bridge: global source finder -> ZeroPlay player.
 *
 * The batch stops at `player.dispatchEvent(new CustomEvent("zp:global-playback"))`
 * and never listens for it. In an app whose player is a single inline script that
 * owns its own state, a dispatched event with no listener is a dead end: the
 * result list renders, the Play button does nothing, and the only streams that
 * ever play are the ones the local finder produced. That is the "source finder
 * does not feed the new player" bug.
 *
 * This module is the other half. It is deliberately engine-free and DOM-shallow:
 *  - it takes a `play(url, meta)` callback, so the caller decides how a stream is
 *    mounted (ZeroPlay passes its own drawPlayer/engine path);
 *  - it normalises whatever the resolver called a result into a playback request
 *    the player understands;
 *  - it enforces the playback boundary (no torrents, no private hosts, no
 *    oversize files) at the moment of play, not just at discovery;
 *  - it dedupes by URL so the same file offered by three sources plays once;
 *  - it remembers failures so one dead host cannot be offered forever.
 */

const PLAYABLE_KINDS = new Set(["direct-file", "hls-playlist", "dash-manifest", "direct-media", "playlist", "manifest"]);

/**
 * Map a resolver result onto { url, kind, title, ... } or null when the result
 * cannot honestly be played in a browser element.
 */
function toPlaybackRequest(result, options) {
  const config = options || {};
  const item = result || {};
  const url = String(item.url || item.href || item.link || "").trim();
  if (!url) return null;
  if (/^(magnet:|ed2k:|thunder:)/i.test(url)) return null;
  if (/\.torrent(\?|$)/i.test(url)) return null;

  const declaredKind = String(item.resultKind || item.kind || item.sourceKind || "").toLowerCase();
  const kind = PLAYABLE_KINDS.has(declaredKind) ? declaredKind : inferKind(url);
  if (!kind) return null;

  if (item.notWebReady === true) return null;
  if (item.behaviorHints && item.behaviorHints.notWebReady === true) return null;

  const bytes = Number(item.bytes || item.sizeBytes || 0);
  const maximum = Number(config.maximumFileSizeBytes) || 5 * 1000 * 1000 * 1000;
  if (bytes > maximum) return null;

  const headers = item.proxyHeaders || (item.behaviorHints && item.behaviorHints.proxyHeaders) || null;
  if (headers && typeof headers === "object" && Object.keys(headers).length) return null;

  return {
    url,
    kind,
    title: String(item.title || item.name || ""),
    sourceId: String(item.sourceId || ""),
    sourceName: String(item.sourceName || ""),
    quality: String(item.quality || ""),
    bytes,
    identityScore: Number(item.identityScore || item.relevanceScore || 0),
    resultId: String(item.id || "")
  };
}

function inferKind(url) {
  const path = String(url || "").split("?")[0].split("#")[0].toLowerCase();
  if (/\.m3u8?$/.test(path)) return "hls-playlist";
  if (/\.mpd$/.test(path)) return "dash-manifest";
  if (/\.(mp4|m4v|mov|webm|mkv|avi|ts)$/.test(path)) return "direct-file";
  return "";
}

/**
 * Rank playable requests the way a viewer would want them: verified exact
 * identity first, then quality, then smaller files (a 400 MB 1080p rip starts
 * faster than a 4 GB one and looks the same on a TV), then stable URL order so
 * the list does not reshuffle between searches.
 */
function rankPlaybackRequests(requests) {
  const list = (Array.isArray(requests) ? requests : []).filter(Boolean);
  const seen = new Set();
  const unique = [];
  for (const request of list) {
    const key = request.url;
    if (seen.has(key)) continue;
    seen.add(key);
    unique.push(request);
  }
  return unique.sort((a, b) => {
    if (b.identityScore !== a.identityScore) return b.identityScore - a.identityScore;
    const aHeight = heightOfQuality(a.quality);
    const bHeight = heightOfQuality(b.quality);
    if (bHeight !== aHeight) return bHeight - aHeight;
    if (a.bytes && b.bytes && a.bytes !== b.bytes) return a.bytes - b.bytes;
    return a.url < b.url ? -1 : a.url > b.url ? 1 : 0;
  });
}

function heightOfQuality(label) {
  const match = /(\d{3,4})p/i.exec(String(label || ""));
  return match ? Number(match[1]) : 0;
}

/**
 * Turns a set of results into a play session: pick number one, remember the
 * ranked queue behind it, and expose next() so a failure over a stream can walk
 * to the next candidate without the viewer touching anything.
 *
 * `play` returns a promise resolving to { ok, reason }. A rejection is treated as
 * a failure and moved past, because a dead host is the common case, not an
 * exception.
 */
function createPlaySession(options) {
  const config = options || {};
  const play = config.play;
  const onPlayback = typeof config.onPlayback === "function" ? config.onPlayback : function () {};
  const onExhausted = typeof config.onExhausted === "function" ? config.onExhausted : function () {};
  const failures = config.failures instanceof Map ? config.failures : new Map();
  let queue = [];
  let index = -1;
  let active = null;
  let cancelled = false;

  return {
    start(requests) {
      queue = rankPlaybackRequests(requests);
      index = -1;
      cancelled = false;
      return attempt();
    },
    next,
    retry: next,
    cancel() {
      cancelled = true;
      active = null;
    },
    getQueue() {
      return queue.slice();
    },
    getActive() {
      return active;
    },
    getFailures() {
      return new Map(failures);
    }
  };

  async function attempt() {
    if (typeof play !== "function") return { ok: false, reason: "no-player" };
    while (index + 1 < queue.length) {
      if (cancelled) return { ok: false, reason: "cancelled" };
      index += 1;
      const candidate = queue[index];
      const failureCount = failures.get(candidate.url) || 0;
      /* Three strikes on the same URL, then never offer it again this session. */
      if (failureCount >= 3) continue;
      active = candidate;
      let outcome;
      try {
        outcome = await play(candidate, { index, total: queue.length, failures: failureCount });
      } catch (error) {
        outcome = { ok: false, reason: (error && error.message) || "play-threw" };
      }
      if (cancelled) return { ok: false, reason: "cancelled" };
      if (outcome && outcome.ok) {
        failures.delete(candidate.url);
        onPlayback({ ok: true, request: candidate, index, total: queue.length });
        return { ok: true, request: candidate, index, total: queue.length };
      }
      failures.set(candidate.url, failureCount + 1);
      onPlayback({ ok: false, request: candidate, reason: (outcome && outcome.reason) || "play-failed", index, total: queue.length });
    }
    active = null;
    onExhausted({ tried: index + 1, total: queue.length });
    return { ok: false, reason: "exhausted", tried: index + 1 };
  }

  async function next() {
    return attempt();
  }
}

/**
 * Wire a results surface to a player without either side knowing the other's
 * internals. `mount` receives the request and returns a teardown function.
 */
function createPlayerBridge(options) {
  const config = options || {};
  const mount = config.mount;
  const failures = new Map();
  let currentSession = null;
  let teardown = null;

  return {
    /** Play the best candidate for a set of resolver results. */
    async playResults(results, context) {
      const requests = rankPlaybackRequests(
        (Array.isArray(results) ? results : []).map((result) => toPlaybackRequest(result, config)).filter(Boolean)
      );
      if (!requests.length) return { ok: false, reason: "nothing-playable" };
      return this.playRequests(requests, context);
    },
    async playRequests(requests, context) {
      this.stop();
      currentSession = createPlaySession({
        failures,
        play: async (request, info) => {
          if (teardown) {
            try {
              teardown();
            } catch (error) {
              void error;
            }
            teardown = null;
          }
          if (typeof config.onBeforePlay === "function") config.onBeforePlay(request, context);
          try {
            teardown = (await mount(request, { ...info, context })) || null;
            return { ok: true };
          } catch (error) {
            return { ok: false, reason: (error && error.message) || "mount-failed" };
          }
        },
        onPlayback: config.onPlayback,
        onExhausted: config.onExhausted
      });
      return currentSession.start(requests);
    },
    async next() {
      if (!currentSession) return { ok: false, reason: "no-session" };
      return currentSession.next();
    },
    stop() {
      if (currentSession) currentSession.cancel();
      currentSession = null;
      if (teardown) {
        try {
          teardown();
        } catch (error) {
          void error;
        }
        teardown = null;
      }
    },
    getFailures() {
      return new Map(failures);
    }
  };
}

module.exports = {
  PLAYABLE_KINDS,
  toPlaybackRequest,
  inferKind,
  rankPlaybackRequests,
  createPlaySession,
  createPlayerBridge,
  heightOfQuality
};
