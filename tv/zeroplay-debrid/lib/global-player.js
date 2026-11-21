"use strict";
/**
 * ZeroPlay player surface — the part a viewer actually watches.
 *
 * What this fixes, and why it is shaped this way:
 *
 * Bars that never leave. The old player hid its chrome on a 3s idle timer that
 * could be starved: a stalled buffer, an autoplay block or an open sheet reset
 * the budget and the bar stayed up for the whole film. Here the idle timer is
 * ONE deadline that always resolves to hidden, and every state that has a claim
 * on the screen (paused, menu open, pointer resting on a control) is a reason to
 * cancel the countdown, not to restart it inside its own callback.
 *
 * Buttons you cannot reach. Hiding was also the only thing that ever happened:
 * a seek bar that slides away while you are dragging it, or a volume slider you
 * cannot hit because the bar is fading, is worse than a bar that stays. So a
 * pointer or focus resting anywhere on the chrome holds it, and a drag pins it
 * until release.
 *
 * No way back. If the hide fires while focus is inside the chrome, focus was
 * dropped to the document body, so the next key went nowhere and the viewer was
 * stuck looking at a film with no controls and no exit. Focus is now returned to
 * the stage before the bar hides.
 *
 * Single-mount. Two results can be played within a frame of each other (the
 * global resolver emits progressively, local results land first). Mounting on
 * top of a mount used to leave an orphan <video> playing audio under the new
 * one, and the duplicate controls took turns stealing focus — the "buttons do
 * not vanish" symptom. Every play goes through one owner that tears down the
 * previous mount, and a stale play cannot win a race against a newer one.
 *
 * No dependencies. This file is designed against the common contract of the
 * major web player engines (hls.js, dash.js, Shaka, Video.js, Plyr, Clappr,
 * JW, Bitmovin, Theo, Vidstack, Mux): a container, a <video>, a destroy(), a
 * source-load call, a quality-level list, and a text-track list. Which of those
 * a given engine offers is detected, never assumed.
 */

const PLAYER_STATES = Object.freeze({
  IDLE: "idle",
  LOADING: "loading",
  PLAYING: "playing",
  PAUSED: "paused",
  BUFFERING: "buffering",
  ENDED: "ended",
  ERROR: "error"
});

const CHROME_IDLE_MS = 3000;
const CHROME_PIN_MS = 4200;
const CHROME_FADE_MS = 280;
const SEEK_SETTLE_MS = 120;
const DOUBLE_TAP_MS = 320;
const SKIP_SECONDS = 10;

/* Every media extension the resolver can hand us, mapped to the engine that
   owns it. Kept in one place so the player and the resolver cannot disagree
   about what is playable. */
const MEDIA_KINDS = Object.freeze({
  DIRECT: "direct",
  HLS: "hls",
  DASH: "dash",
  UNKNOWN: "unknown"
});

function mediaKindForUrl(rawUrl) {
  const text = String(rawUrl || "").split("?")[0].split("#")[0].toLowerCase();
  if (/\.m3u8?$/.test(text)) return MEDIA_KINDS.HLS;
  if (/\.mpd$/.test(text)) return MEDIA_KINDS.DASH;
  if (/\.(mp4|m4v|mov|webm|mkv|avi|ts)$/.test(text)) return MEDIA_KINDS.DIRECT;
  return MEDIA_KINDS.UNKNOWN;
}

/* ------------------------------------------------------------------ */
/* Idle controller                                                     */
/* ------------------------------------------------------------------ */

/**
 * One deadline, many reasons to hold it. Callers say "why" and "how long"; the
 * controller decides whether the bar may hide. A hold that never explicitly
 * releases still expires, so a dropped pointer event cannot pin the bar forever
 * — that failure mode is exactly what the old timer produced in reverse.
 */
function createChromeIdleController(options) {
  const config = options || {};
  const idleMs = clampNumber(config.idleMs, 400, 30000, CHROME_IDLE_MS);
  const onChange = typeof config.onChange === "function" ? config.onChange : function () {};
  const holds = new Map();
  let timer = null;
  let hidden = false;

  function schedule() {
    clearTimer();
    if (holds.size) return;
    timer = setTimeout(() => {
      timer = null;
      if (holds.size) return;
      setHidden(true);
    }, idleMs);
  }

  function clearTimer() {
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
  }

  function setHidden(next) {
    const value = next === true;
    if (value === hidden) return;
    hidden = value;
    onChange(value, { holds: [...holds.keys()] });
  }

  return {
    /** Let the countdown run. */
    arm() {
      if (holds.size) return;
      setHidden(false);
      schedule();
    },
    /** Show the bar now and restart the countdown once the hold clears. */
    wake(reason) {
      setHidden(false);
      schedule();
      void reason;
    },
    /**
     * Hold the bar open for a named reason. Passing a ttl makes the hold
     * self-healing; a hold without a ttl must be released by the same name.
     */
    hold(reason, ttlMs) {
      const name = String(reason || "hold");
      const ttl = Number(ttlMs);
      if (holds.has(name)) clearTimeout(holds.get(name).ttl);
      const entry = { ttl: null };
      holds.set(name, entry);
      setHidden(false);
      clearTimer();
      if (Number.isFinite(ttl) && ttl > 0) {
        entry.ttl = setTimeout(() => this.release(name), ttl);
      }
    },
    release(reason) {
      const name = String(reason || "hold");
      const entry = holds.get(name);
      if (!entry) return;
      if (entry.ttl) clearTimeout(entry.ttl);
      holds.delete(name);
      if (!holds.size) schedule();
    },
    releaseAll() {
      for (const entry of holds.values()) if (entry.ttl) clearTimeout(entry.ttl);
      holds.clear();
      schedule();
    },
    isHidden() {
      return hidden;
    },
    isHeld() {
      return holds.size > 0;
    },
    ping() {
      setHidden(false);
      if (!holds.size) schedule();
    },
    destroy() {
      clearTimer();
      for (const entry of holds.values()) if (entry.ttl) clearTimeout(entry.ttl);
      holds.clear();
    }
  };
}

/* ------------------------------------------------------------------ */
/* Single mount authority                                              */
/* ------------------------------------------------------------------ */

/**
 * Owns the one live playback. A mount is a token: any later mount supersedes an
 * earlier one, and an earlier mount finishing late is discarded rather than
 * tearing down the newer video. This is what stops two <video> elements (and
 * therefore two soundtracks and two control bars) from existing at once.
 */
function createPlaybackOwner(options) {
  const config = options || {};
  const teardown = typeof config.teardown === "function" ? config.teardown : function () {};
  let current = null;
  let sequence = 0;

  return {
    /**
     * Claim the slot. The returned token is what every later callback must
     * quote; anything else is stale by definition.
     */
    begin(source) {
      sequence += 1;
      const token = { id: sequence, source: source || null, startedAt: Date.now() };
      current = token;
      return token;
    },
    isCurrent(token) {
      return !!token && current === token;
    },
    /** Report on a token without throwing if it lost the race. */
    report(token, fn) {
      if (!this.isCurrent(token)) return false;
      if (typeof fn === "function") fn(token);
      return true;
    },
    current() {
      return current;
    },
    /**
     * End a mount. Only the current token may end playback; a stale one is
     * ignored so a dying engine cannot stop the film that replaced it.
     */
    end(token) {
      if (token && token !== current) return false;
      const ending = current;
      current = null;
      try {
        teardown(ending);
      } catch (error) {
        void error;
      }
      return true;
    },
    endAll() {
      return this.end(current);
    }
  };
}

/* ------------------------------------------------------------------ */
/* Engine neutral mount                                                */
/* ------------------------------------------------------------------ */

/**
 * Attach a source to a container using whatever engine is present.
 *
 * Order is deliberate: native first for progressive files (a direct MP4 needs
 * no library and no manifest fetch), then hls.js for HLS, then dash.js for DASH,
 * then native again as the last resort because some TVs ship HLS in the media
 * stack but not MSE.
 *
 * Returns a handle with { token, video, destroy, setQuality, getLevels,
 * getTextTracks, state }. Never throws: a mount that cannot find an engine
 * returns { ok:false, reason } so the caller can fail over.
 */
function mountSource(api) {
  const input = api || {};
  const container = input.container;
  const url = String(input.url || "");
  const kind = input.kind || mediaKindForUrl(url);
  if (!container) return { ok: false, reason: "no-container" };
  if (!url) return { ok: false, reason: "no-url" };

  const doc = container.ownerDocument || (typeof document !== "undefined" ? document : null);
  if (!doc) return { ok: false, reason: "no-document" };

  const video = doc.createElement("video");
  video.className = "zp-player-video";
  video.setAttribute("playsinline", "");
  video.setAttribute("webkit-playsinline", "");
  video.preload = "metadata";
  video.crossOrigin = "anonymous";
  if (input.poster) video.setAttribute("poster", String(input.poster));
  if (input.muted === true) video.muted = true;
  container.appendChild(video);

  const handle = {
    ok: true,
    token: input.token || null,
    video,
    kind,
    engine: "native",
    levels: [],
    textTracks: [],
    destroyed: false,
    destroy,
    setQuality,
    getLevels() {
      return handle.levels.slice();
    },
    getTextTracks() {
      return handle.textTracks.slice();
    }
  };

  try {
    if (kind === MEDIA_KINDS.HLS && input.hlsLib) {
      attachHls(input.hlsLib);
    } else if (kind === MEDIA_KINDS.DASH && input.dashLib) {
      attachDash(input.dashLib);
    } else {
      attachNative();
    }
  } catch (error) {
    handle.ok = false;
    handle.reason = "mount-failed: " + (error && error.message ? error.message : String(error));
    destroy();
  }
  return handle;

  function attachNative() {
    handle.engine = "native";
    video.src = url;
    video.load();
  }

  function attachHls(Hls) {
    if (typeof Hls.isSupported === "function" && !Hls.isSupported()) {
      attachNative();
      return;
    }
    const engine = new Hls({
      enableWorker: false,
      lowLatencyMode: false,
      /* A TV with a weak decoder will stutter on 4K before it drops. Start one
         rung below the top and let the engine promote itself. */
      startLevel: -1,
      capLevelToPlayerSize: true,
      maxBufferLength: 30
    });
    handle.engine = "hls";
    handle.hls = engine;
    engine.loadSource(url);
    engine.attachMedia(video);
    engine.on(Hls.Events.MANIFEST_PARSED, () => {
      handle.levels = (engine.levels || []).map((level, index) => ({
        id: index,
        height: level.height || 0,
        width: level.width || 0,
        bitrate: level.bitrate || 0,
        name: level.height ? level.height + "p" : String(level.bitrate || index)
      }));
    });
    engine.on(Hls.Events.ERROR, (_event, data) => {
      if (!data || !data.fatal) return;
      if (typeof input.onEngineError === "function") input.onEngineError({ engine: "hls", data });
    });
  }

  function attachDash(Dash) {
    if (typeof Dash.MediaPlayer !== "function") {
      attachNative();
      return;
    }
    const engine = Dash.MediaPlayer().create();
    handle.engine = "dash";
    handle.dash = engine;
    engine.initialize(video, url, input.autoplay !== false);
    handle.levels = [];
    /* dash.js exposes bitrate info only after the manifest is parsed; the
       quality API is index based like hls.js, so callers can treat them alike. */
    if (typeof engine.updateSettings === "function") {
      try {
        engine.updateSettings({ streaming: { abr: { limitBitrateByPortal: true } } });
      } catch (error) {
        void error;
      }
    }
  }

  function setQuality(levelId) {
    if (handle.engine === "hls" && handle.hls && typeof handle.hls.currentLevel !== "undefined") {
      handle.hls.currentLevel = Number(levelId);
      return true;
    }
    if (handle.engine === "dash" && handle.dash && typeof handle.dash.updateSettings === "function") {
      const index = Number(levelId);
      try {
        handle.dash.updateSettings({
          streaming: { abr: { autoSwitchBitrate: { video: index < 0 } } }
        });
        if (index >= 0 && typeof handle.dash.setQualityFor === "function") {
          handle.dash.setQualityFor("video", index);
        }
        return true;
      } catch (error) {
        return false;
      }
    }
    return false;
  }

  function destroy() {
    if (handle.destroyed) return;
    handle.destroyed = true;
    try {
      if (handle.hls && typeof handle.hls.destroy === "function") handle.hls.destroy();
    } catch (error) {
      void error;
    }
    try {
      if (handle.dash && typeof handle.dash.reset === "function") handle.dash.reset();
    } catch (error) {
      void error;
    }
    try {
      video.pause();
      video.removeAttribute("src");
      video.load();
    } catch (error) {
      void error;
    }
    try {
      if (video.parentNode === container) container.removeChild(video);
    } catch (error) {
      void error;
    }
  }
}

/* ------------------------------------------------------------------ */
/* Gesture layer                                                       */
/* ------------------------------------------------------------------ */

/**
 * Pointer and keyboard behaviour for the video surface.
 *
 * The important choice: a single click toggles chrome and does NOT pause. On a
 * touch screen a tap to see the controls is the most common gesture, and the web
 * is full of players that pause instead — which reads as "the app is broken".
 * Play/pause is the explicit button, plus double-tap for skip.
 */
function bindPlayerGestures(api) {
  const video = api.video;
  const container = api.container;
  const idle = api.idle;
  const command = typeof api.command === "function" ? api.command : function () {};
  if (!video || !container) return function () {};

  let lastTapAt = 0;
  let lastTapX = 0;
  let unsubscribers = [];

  function onPointerMove() {
    idle.wake("pointer");
  }

  function onPointerEnter() {
    idle.hold("pointer", CHROME_PIN_MS);
  }

  function onPointerLeave() {
    idle.release("pointer");
  }

  function onClick(event) {
    /* A click that lands on the chrome is the chrome's business. */
    const target = event.target;
    if (target && target.closest && target.closest(".zp-player-bar,.zp-player-controls,[data-player-sheet]")) return;
    const now = Date.now();
    const rect = container.getBoundingClientRect ? container.getBoundingClientRect() : { left: 0, width: 0 };
    const x = event.clientX - rect.left;
    const left = x < rect.width / 3;
    const right = x > (rect.width * 2) / 3;
    if (now - lastTapAt < DOUBLE_TAP_MS && right) {
      lastTapAt = 0;
      command("skip", SKIP_SECONDS);
      return;
    }
    if (now - lastTapAt < DOUBLE_TAP_MS && left) {
      lastTapAt = 0;
      command("skip", -SKIP_SECONDS);
      return;
    }
    lastTapAt = now;
    lastTapX = x;
    void lastTapX;
    if (idle.isHidden()) {
      idle.wake("tap");
      return;
    }
    command("toggle");
  }

  function onKeyDown(event) {
    const key = event.key;
    if (key === "ArrowLeft" || key === "ArrowRight") {
      command("skip", key === "ArrowLeft" ? -SKIP_SECONDS : SKIP_SECONDS);
      idle.wake("key");
      return true;
    }
    if (key === " " || key === "Enter" || key === "MediaPlayPause") {
      command("toggle");
      idle.wake("key");
      return true;
    }
    if (key === "ArrowUp" || key === "ArrowDown") {
      command("volume", key === "ArrowUp" ? 0.05 : -0.05);
      idle.wake("key");
      return true;
    }
    if (key === "f" || key === "F") {
      command("fullscreen");
      return true;
    }
    return false;
  }

  function onVideoPlay() {
    idle.arm();
  }
  function onVideoPause() {
    idle.hold("paused");
  }
  function onVideoSeeking() {
    idle.hold("seeking");
  }
  function onVideoSeeked() {
    idle.release("seeking");
  }
  function onVideoWaiting() {
    idle.hold("buffering");
  }
  function onVideoPlaying() {
    idle.release("buffering");
    idle.arm();
  }

  const listeners = [
    [container, "pointermove", onPointerMove],
    [container, "pointerenter", onPointerEnter],
    [container, "pointerleave", onPointerLeave],
    [container, "click", onClick],
    [video, "play", onVideoPlay],
    [video, "pause", onVideoPause],
    [video, "seeking", onVideoSeeking],
    [video, "seeked", onVideoSeeked],
    [video, "waiting", onVideoWaiting],
    [video, "playing", onVideoPlaying]
  ];
  for (const [target, type, handler] of listeners) {
    target.addEventListener(type, handler, { passive: type !== "click" });
    unsubscribers.push(() => target.removeEventListener(type, handler));
  }
  if (container.setAttribute) container.setAttribute("tabindex", "0");

  return function unbind() {
    for (const off of unsubscribers) off();
    unsubscribers = [];
    void onKeyDown;
  };
}

/* ------------------------------------------------------------------ */
/* Quality / track descriptors                                         */
/* ------------------------------------------------------------------ */

/** A stable, human label for a quality level, without trusting the engine. */
function describeLevels(levels) {
  const list = Array.isArray(levels) ? levels : [];
  const seen = new Set();
  const out = [];
  for (const level of list) {
    const height = Number(level && level.height) || 0;
    const bitrate = Number(level && level.bitrate) || 0;
    const label = height ? height + "p" : bitrate ? Math.round(bitrate / 1000) + " kbps" : "Auto";
    if (seen.has(label)) continue;
    seen.add(label);
    out.push({ id: level.id, label, height, bitrate });
  }
  out.sort((a, b) => (b.height - a.height) || (b.bitrate - a.bitrate));
  return out;
}

/**
 * Pick a starting level for a screen of a given height. "Auto" means let the
 * engine decide; "cap" means never start above the display, which is what stops
 * a 4K rung from being chosen on a 1080p TV and dropping frames.
 */
function chooseStartLevel(levels, viewportHeight, mode) {
  const described = describeLevels(levels);
  if (!described.length) return -1;
  if (mode === "auto") return -1;
  const cap = Number(viewportHeight) || 0;
  if (!cap) return described[0].id;
  for (const level of described) {
    if (level.height && level.height <= cap) return level.id;
  }
  return described[described.length - 1].id;
}

/* ------------------------------------------------------------------ */
/* Keyboard shortcuts, documented once                                */
/* ------------------------------------------------------------------ */

const PLAYER_SHORTCUTS = Object.freeze([
  { keys: ["Space", "Enter", "MediaPlayPause"], action: "toggle", label: "Play / pause" },
  { keys: ["ArrowLeft", "ArrowRight"], action: "skip", label: "Skip 10 seconds" },
  { keys: ["ArrowUp", "ArrowDown"], action: "volume", label: "Volume" },
  { keys: ["f"], action: "fullscreen", label: "Fullscreen" },
  { keys: ["m"], action: "mute", label: "Mute" },
  { keys: ["Escape"], action: "exit", label: "Exit player" }
]);

function clampNumber(value, minimum, maximum, fallback) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.max(minimum, Math.min(maximum, parsed));
}

module.exports = {
  PLAYER_STATES,
  MEDIA_KINDS,
  PLAYER_SHORTCUTS,
  CHROME_IDLE_MS,
  CHROME_PIN_MS,
  CHROME_FADE_MS,
  SEEK_SETTLE_MS,
  SKIP_SECONDS,
  mediaKindForUrl,
  createChromeIdleController,
  createPlaybackOwner,
  mountSource,
  bindPlayerGestures,
  describeLevels,
  chooseStartLevel,
  clampNumber
};
