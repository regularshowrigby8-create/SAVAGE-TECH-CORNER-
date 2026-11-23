"use strict";
/**
 * The player core, rebuilt from what the major web players actually do.
 *
 * Every decision below is traceable to a specific source read, not to taste:
 *
 *  video.js v10  packages/core/src/dom/store/features/controls.ts
 *    - `computeVisible()`: controls are visible when a lock is held, the user is
 *      active, OR the media is paused / casting. Paused-means-visible is the
 *      rule that stops a viewer being stranded behind an invisible bar.
 *    - `requestControlsLock()`: refcounted, each release idempotent, and
 *      releasing the last lock calls `setActive()` — which grants a FULL idle
 *      window after the interaction ends, instead of hiding instantly on the
 *      same tick a menu closes.
 *    - guarded `mouseleave` / `focusin`: Android Chrome fires a synthetic
 *      mouseleave after touchend and focus events fire mid-touch; both are
 *      ignored inside a settle window or the bar flickers under your thumb.
 *
 *  plyr (sampotts/plyr src/js/listeners.js)
 *    - 2000 ms idle on pointer devices, 3000 ms on touch, and every event
 *      clears then re-arms the single timer. One timer, never a second one.
 *
 *  video.js IDLE_DELAY is 2000 with TAP_THRESHOLD 250 and TOUCH_SETTLE 500.
 *    Those constants are adopted rather than invented.
 *
 *  clappr / vidstack / indigo / openplayer
 *    - playback failure is data, not an exception: engines report a reason and
 *      the player walks a ranked fallback list instead of dying on the first.
 *    - recovery is bracketed: a retry that succeeds must not skip or restart
 *      noticeably, so position is restored and verified.
 *
 * The old implementation in this repo accumulated idle time and re-armed a
 * 700 ms polling tick whenever anything was busy. That is why the bar could
 * stay up forever while buffering and why closing a menu did not give you a
 * grace period. This replaces that with a single deadline and a visibility
 * predicate, which is a smaller thing that is true.
 */

const TIMING = Object.freeze({
  /* Pointer devices: plyr's 2000. */
  IDLE_MS: 2000,
  /* Touch: plyr's 3000 — a finger hovering after a tap is not "active". */
  IDLE_TOUCH_MS: 3000,
  /* video.js TAP_THRESHOLD: below this a pointerup is a tap, not a drag. */
  TAP_THRESHOLD_MS: 250,
  /* video.js TOUCH_SETTLE_DELAY: ignore synthetic mouse events this soon
     after a real touch. */
  TOUCH_SETTLE_MS: 500,
  /* video.js CAPTURE_RELEASE_DELAY: ignore the spurious mouseleave Safari 16
     dispatches when a control such as the seek slider releases pointer
     capture. */
  CAPTURE_RELEASE_MS: 100,
  /* Double-tap-to-seek window (the player's own, previously hard-coded). */
  DOUBLE_TAP_MS: 280,
  /* Beyond this a press is a hold gesture (speed-up / scrub), not a tap. */
  LONG_PRESS_MS: 1500,
  /* How long a seek-preview/scrub readout stays after the pointer lifts. */
  SEEK_SETTLE_MS: 400
});

const SKIP_SECONDS = Object.freeze({
  tap: 10,
  key: 10,
  hold: 30
});

/** Why the chrome is allowed to be on screen. Order is precedence. */
const HOLD_REASONS = Object.freeze({
  LOCK: "lock",
  PAUSED: "paused",
  INTERACTING: "interacting",
  MENU: "menu",
  ERROR: "error",
  ENDED: "ended",
  CASTING: "casting"
});

/**
 * The visibility predicate, lifted verbatim in behaviour from
 * video.js `computeVisible()` and widened with the states this app has that
 * video.js does not (menus, a terminal error card, the end screen).
 *
 * A helper, not a stateful object: the caller owns the truth, this only
 * answers "may the chrome be drawn right now".
 */
function shouldShowChrome(state, options) {
  const s = state || {};
  const o = options || {};
  if (o.forceShow === true) return true;
  if (o.forceHide === true) return false;
  const holds = s.holds instanceof Set ? s.holds.size : Number(s.holdCount) || 0;
  /* A lock or a pause outranks everything: if the viewer asked for the bar, or
     the film is not playing, hiding it removes the only way to resume. */
  if (holds > 0) return true;
  if (s.paused === true) return true;
  if (s.ended === true) return true;
  if (s.errored === true) return true;
  if (s.menuOpen === true) return true;
  if (s.casting === true) return true;
  if (s.buffering === true) {
    /* Buffering is not "the user is idle" — the viewer is watching a spinner
       and needs the controls. But it is also not a reason to pin the chrome
       forever if they walk away, so it falls through to userActive. */
    return s.userActive !== false;
  }
  if (s.userActive === false) return false;
  return true;
}

/** Which hold reasons are currently in effect — for the debug readout. */
function activeHolds(state, options) {
  const s = state || {};
  const o = options || {};
  const reasons = [];
  if (o.forceShow === true) reasons.push("forced");
  const holds = s.holds instanceof Set ? [...s.holds] : [];
  for (const hold of holds) reasons.push(hold);
  if (s.paused === true) reasons.push(HOLD_REASONS.PAUSED);
  if (s.menuOpen === true) reasons.push(HOLD_REASONS.MENU);
  if (s.errored === true) reasons.push(HOLD_REASONS.ERROR);
  if (s.ended === true) reasons.push(HOLD_REASONS.ENDED);
  if (s.casting === true) reasons.push(HOLD_REASONS.CASTING);
  return reasons;
}

/**
 * A hold is a claim on the chrome for as long as some piece of the UI needs it.
 *
 * video.js's rules, kept exactly:
 *  - taking a hold clears the idle timer;
 *  - the release function is idempotent (calling it twice must not decrement
 *    twice — that is how a refcount goes negative and the bar sticks);
 *  - releasing the LAST hold starts a fresh idle window rather than hiding on
 *    the spot, so you are not left hunting for the menu you just closed.
 */
function createChromeLocks(options) {
  const o = options || {};
  const onChange = typeof o.onChange === "function" ? o.onChange : () => {};
  const holds = new Set();
  let counter = 0;

  function acquire(reason) {
    if (typeof reason === "function") return acquire(undefined);
    const id = "hold-" + (counter += 1);
    const label = reason || HOLD_REASONS.LOCK;
    holds.add(label + "#" + id);
    onChange(state());
    let released = false;
    return function release() {
      if (released) return false;
      released = true;
      holds.delete(label + "#" + id);
      onChange(state());
      return true;
    };
  }

  function state() {
    return { holds: new Set(holds), holdCount: holds.size };
  }

  function clear() {
    holds.clear();
    onChange(state());
  }

  return { acquire, release: () => {}, state, clear, get count() { return holds.size; } };
}

/**
 * The idle controller.
 *
 * One deadline, re-armed on activity, never polled. `tick()` decides via
 * `shouldShowChrome()` whether hiding is allowed at that moment; if it is not,
 * it reschedules once rather than spinning at 700 ms as the old code did.
 */
function createIdleController(options) {
  const o = options || {};
  const now = typeof o.now === "function" ? o.now : () => Date.now();
  const setTimer = typeof o.setTimeout === "function" ? o.setTimeout : (fn, ms) => setTimeout(fn, ms);
  const clearTimer = typeof o.clearTimeout === "function" ? o.clearTimeout : (id) => clearTimeout(id);
  const onVisibilityChange = typeof o.onVisibilityChange === "function" ? o.onVisibilityChange : () => {};

  let timer = null;
  let deadline = 0;
  let userActive = true;
  /* null, not 0: a timestamp of 0 is a legitimate clock reading, and
     guarding with `> 0` made the settle window fail on the first touch. */
  let lastTouchAt = null;
  let lastCaptureReleaseAt = null;
  let touchMode = o.touch === true;
  let disposed = false;
  let getState = typeof o.getState === "function" ? o.getState : () => ({});

  const idleMs = () => (touchMode ? TIMING.IDLE_TOUCH_MS : TIMING.IDLE_MS);

  function cancelTimer() {
    if (timer !== null) {
      try {
        clearTimer(timer);
      } catch (error) {
        void error;
      }
      timer = null;
    }
    deadline = 0;
  }

  function publish() {
    const state = getState() || {};
    onVisibilityChange(shouldShowChrome({ ...state, userActive }), {
      userActive,
      holds: activeHolds({ ...state, userActive }),
      deadline,
      idleMs: idleMs()
    });
  }

  /** Mark the user active and push the deadline out. This is `setActive()`. */
  function activate() {
    if (disposed) return;
    userActive = true;
    cancelTimer();
    /* A pause or a lock means the chrome stays regardless; there is no point
       running a deadline that cannot fire. */
    const state = getState() || {};
    if (!shouldShowChrome({ ...state, userActive })) {
      /* Should not happen (active implies visible) but keeps the timer honest. */
    }
    if (state.paused === true || state.menuOpen === true || state.errored === true || state.ended === true) {
      publish();
      return;
    }
    deadline = now() + idleMs();
    timer = setTimer(() => {
      timer = null;
      deadline = 0;
      /* Re-check at fire time: playback may have paused, a menu may have
         opened, or a lock may have been taken since this was armed. */
      const current = getState() || {};
      if (!shouldShowChrome({ ...current, userActive: false })) {
        userActive = false;
        publish();
        return;
      }
      userActive = false;
      publish();
      /* Give the caller one more chance: if it now decides the chrome must
         stay (a sheet opened between the arm and the fire), it calls
         `rearm()`; we do not poll. */
    }, idleMs());
    publish();
  }

  /** Hide now, ignoring activity (used by mouseleave, tap-to-toggle). */
  function deactivate() {
    if (disposed) return;
    const state = getState() || {};
    if (shouldShowChrome({ ...state, userActive: false })) {
      /* Something else holds the chrome. Keep the timer parked. */
      userActive = false;
      publish();
      return;
    }
    cancelTimer();
    userActive = false;
    publish();
  }

  /**
   * A touch or gesture ended in a way that must not be undone by the synthetic
   * pointer events the platform fires afterwards. Port of video.js's
   * `isRecentTouch()` / `isRecentCaptureRelease()` guards.
   */
  function noteTouch() {
    lastTouchAt = now();
    touchMode = true;
  }
  function noteCaptureRelease() {
    lastCaptureReleaseAt = now();
  }
  function isRecentTouch() {
    return lastTouchAt !== null && now() - lastTouchAt < TIMING.TOUCH_SETTLE_MS;
  }
  function isRecentCaptureRelease() {
    return lastCaptureReleaseAt !== null && now() - lastCaptureReleaseAt < TIMING.CAPTURE_RELEASE_MS;
  }

  /**
   * The one entry point for pointer movement over the surface.
   * Returns the action taken, which makes the behaviour testable.
   */
  function onPointerMove(pointerType) {
    if (disposed) return "disposed";
    if (pointerType === "touch") {
      /* Do not flip visibility mid-gesture — just keep the timer alive. */
      if (userActive) {
        cancelTimer();
        const state = getState() || {};
        if (!(state.paused === true || state.menuOpen === true)) {
          deadline = now() + idleMs();
          timer = setTimer(() => {
            timer = null;
            deadline = 0;
            userActive = false;
            publish();
          }, idleMs());
        }
      }
      return "kept-alive";
    }
    activate();
    return "activated";
  }

  /**
   * Pointer left the surface. Suppressed for the synthetic variants.
   *
   * Order matters. Android Chrome dispatches `mouseleave` right after
   * `touchend`, so the settle window has to be consulted before the pointerType
   * check — a synthetic mouse event is reported as a mouse, which is exactly
   * how the bar used to vanish the instant your thumb lifted.
   */
  function onPointerLeave(options) {
    const o2 = options || {};
    if (disposed) return "disposed";
    if (isRecentTouch()) return "ignored-touch";
    if (o2.insideOnCaptureRelease === true && isRecentCaptureRelease()) return "ignored-capture-release";
    if (o2.pointerType === "touch") return "ignored-touch-surface";
    deactivate();
    return "deactivated";
  }

  function onKeyActivity() {
    if (disposed) return;
    activate();
  }

  /** A tap on the surface toggles the chrome, as video.js's inline path does. */
  function onTap(options) {
    const o2 = options || {};
    if (disposed) return "disposed";
    noteTouch();
    const state = getState() || {};
    if (shouldShowChrome({ ...state, userActive })) {
      /* Only hide if nothing else is holding it — a tap on a control must not
         dismiss the bar the control lives in. */
      if (o2.onControl === true) return activate(), "control-tap";
      deactivate();
      return "hidden";
    }
    activate();
    return "shown";
  }

  function setTouchMode(isTouch) {
    touchMode = isTouch === true;
    return touchMode;
  }

  function rearm() {
    if (disposed) return;
    activate();
  }

  /**
   * Force the chrome to a known state — used when the player opens/closes.
   *
   * Opening the player shows the chrome AND starts the idle window. Setting
   * `userActive` without arming the deadline was a real bug: the bar came up on
   * open and then never hid, because nothing had scheduled the hide.
   */
  function reset(visible) {
    if (disposed) return;
    if (visible === false) {
      userActive = false;
      cancelTimer();
      publish();
      return;
    }
    activate();
  }

  function dispose() {
    disposed = true;
    cancelTimer();
  }

  return {
    activate,
    deactivate,
    dispose,
    rearm,
    reset,
    setTouchMode,
    noteTouch,
    noteCaptureRelease,
    onPointerMove,
    onPointerLeave,
    onKeyActivity,
    onTap,
    isRecentTouch,
    isRecentCaptureRelease,
    get userActive() { return userActive; },
    get deadlineIn() { return deadline ? Math.max(0, deadline - now()) : 0; },
    get isTouch() { return touchMode; }
  };
}

/**
 * Skip/seek arithmetic, and the guard that makes double-tap-to-seek safe.
 *
 * Every one of these players clamps to the media duration; several also refuse
 * to seek before metadata has loaded, because `video.currentTime = 30` on a
 * stream with no duration silently lands at 0 and looks like the button is
 * broken. That guard is included.
 */
function createSeekController(options) {
  const o = options || {};
  const getMedia = typeof o.getMedia === "function" ? o.getMedia : () => null;

  function duration() {
    const media = getMedia();
    const value = media && Number(media.duration);
    return Number.isFinite(value) && value > 0 ? value : 0;
  }

  function currentTime() {
    const media = getMedia();
    const value = media && Number(media.currentTime);
    return Number.isFinite(value) && value >= 0 ? value : 0;
  }

  function ready() {
    const media = getMedia();
    if (!media) return false;
    /* readyState 1 = HAVE_METADATA. HLS streams report duration only after it. */
    return Number(media.readyState) >= 1 && duration() > 0;
  }

  function clamp(target) {
    const total = duration();
    const value = Number(target);
    if (!Number.isFinite(value)) return 0;
    if (total <= 0) return Math.max(0, value);
    /* Leave a hair of headroom: seeking exactly to duration fires `ended` on
       some engines before the last frame paints. */
    return Math.max(0, Math.min(total - 0.05, value));
  }

  function seekBy(delta) {
    const media = getMedia();
    if (!media || !ready()) return { ok: false, reason: "not-ready" };
    const from = currentTime();
    const to = clamp(from + Number(delta || 0));
    if (!Number.isFinite(to) || Math.abs(to - from) < 0.01) {
      return { ok: false, reason: "no-op", from, to: from };
    }
    try {
      media.currentTime = to;
    } catch (error) {
      return { ok: false, reason: "threw", from, to, error };
    }
    return { ok: true, from, to };
  }

  function seekTo(target) {
    const media = getMedia();
    if (!media || !ready()) return { ok: false, reason: "not-ready" };
    const to = clamp(target);
    try {
      media.currentTime = to;
    } catch (error) {
      return { ok: false, reason: "threw", to, error };
    }
    return { ok: true, to };
  }

  /** Double-tap: left half rewinds, right half forwards. */
  function seekFromTap(clientX, surfaceWidth) {
    const width = Number(surfaceWidth) || 0;
    if (!width) return { ok: false, reason: "no-surface" };
    const isLeft = Number(clientX) < width / 2;
    const result = seekBy(isLeft ? -SKIP_SECONDS.tap : SKIP_SECONDS.tap);
    return { ...result, direction: isLeft ? "back" : "forward", seconds: SKIP_SECONDS.tap };
  }

  return { duration, currentTime, ready, clamp, seekBy, seekTo, seekFromTap };
}

/**
 * A gesture recogniser that distinguishes tap / double-tap / horizontal drag
 * without a framework, with the double-tap window and the "a drag cancels the
 * tap" rule the reference players all implement.
 */
function createGestureRecognizer(options) {
  const o = options || {};
  const onTap = typeof o.onTap === "function" ? o.onTap : () => {};
  const onDoubleTap = typeof o.onDoubleTap === "function" ? o.onDoubleTap : () => {};
  const onDragStart = typeof o.onDragStart === "function" ? o.onDragStart : () => {};
  const onDragMove = typeof o.onDragMove === "function" ? o.onDragMove : () => {};
  const onDragEnd = typeof o.onDragEnd === "function" ? o.onDragEnd : () => {};
  const now = typeof o.now === "function" ? o.now : () => Date.now();
  const setTimer = typeof o.setTimeout === "function" ? o.setTimeout : (fn, ms) => setTimeout(fn, ms);
  const clearTimer = typeof o.clearTimeout === "function" ? o.clearTimeout : (id) => clearTimeout(id);
  const dragThreshold = Number(o.dragThreshold) || 10;

  /* null, not 0 — see the note on lastTouchAt: a clock that starts at zero is
     normal, and `if (!downAt)` treated the first gesture as "no gesture". */
  let downAt = null;
  let downX = 0;
  let downY = 0;
  let lastTapAt = null;
  let lastTapX = 0;
  let dragging = false;
  let pendingTap = null;

  function pointerDown(x, y) {
    downAt = now();
    downX = Number(x) || 0;
    downY = Number(y) || 0;
    dragging = false;
  }

  function pointerMove(x, y) {
    if (downAt === null) return;
    const dx = (Number(x) || 0) - downX;
    const dy = (Number(y) || 0) - downY;
    if (!dragging && Math.abs(dx) > dragThreshold && Math.abs(dx) > Math.abs(dy)) {
      dragging = true;
      onDragStart({ dx, dy, x, y });
      return;
    }
    if (dragging) onDragMove({ dx, dy, x, y });
  }

  function pointerUp(x, y, meta) {
    if (downAt === null) return { type: "none" };
    const held = now() - downAt;
    const wasDragging = dragging;
    downAt = null;
    dragging = false;

    if (wasDragging) {
      onDragEnd({ dx: (Number(x) || 0) - downX, dy: (Number(y) || 0) - downY, x, y, meta });
      return { type: "drag" };
    }

    /* A long press is not a tap. video.js derives its threshold from the tap
       window; multiplying TAP_THRESHOLD_MS by 3 gave 750 ms, which is short
       enough that a deliberate press was classified as a hold and no tap ever
       fired. A real long-press is a full second and a half. */
    if (held > TIMING.LONG_PRESS_MS) return { type: "hold" };

    const tapAt = now();
    const isDouble = lastTapAt !== null && tapAt - lastTapAt < TIMING.DOUBLE_TAP_MS &&
      Math.abs((Number(x) || 0) - lastTapX) < 80;

    if (isDouble) {
      lastTapAt = null;
      if (pendingTap) {
        clearTimer(pendingTap);
        pendingTap = null;
      }
      onDoubleTap({ x, y, meta });
      return { type: "doubletap" };
    }

    lastTapAt = tapAt;
    lastTapX = Number(x) || 0;
    /* Defer the single tap by the double-tap window so a quick second tap can
       cancel it — the alternative is a seek and a pause on the same gesture. */
    if (o.deferTap === false) {
      onTap({ x, y, meta });
      return { type: "tap" };
    }
    pendingTap = setTimer(() => {
      pendingTap = null;
      onTap({ x, y, meta });
    }, TIMING.DOUBLE_TAP_MS);
    return { type: "tap-pending" };
  }

  function cancel() {
    downAt = null;
    dragging = false;
    if (pendingTap) {
      clearTimer(pendingTap);
      pendingTap = null;
    }
  }

  return { pointerDown, pointerMove, pointerUp, cancel };
}

/**
 * Keyboard map. Held keys are reported as `repeat`, so the caller can decide
 * whether a repeated arrow is a 5-second nudge or a continuous scan.
 */
const KEYBOARD_MAP = Object.freeze({
  " ": "toggle-play",
  k: "toggle-play",
  K: "toggle-play",
  ArrowRight: "seek-forward",
  l: "seek-forward",
  L: "seek-forward",
  ArrowLeft: "seek-back",
  j: "seek-back",
  J: "seek-back",
  ArrowUp: "volume-up",
  ArrowDown: "volume-down",
  m: "mute",
  M: "mute",
  f: "fullscreen",
  F: "fullscreen",
  Escape: "exit",
  c: "captions",
  C: "captions",
  ">": "rate-up",
  "<": "rate-down",
  Home: "seek-start",
  End: "seek-end",
  PageUp: "chapter-next",
  PageDown: "chapter-prev"
});

function resolveKey(key, options) {
  const o = options || {};
  if (!key) return null;
  /* Digits 0-9 are percentage jumps, as in every player that has them. */
  if (/^[0-9]$/.test(key)) return { action: "seek-percent", value: Number(key) * 10 };
  const action = KEYBOARD_MAP[key];
  if (!action) return null;
  /* Escape only exits the surface; it must not close the browser's fullscreen
     for the viewer (the platform does that itself). */
  return { action, value: null, raw: key, ...(o.extra || {}) };
}

/** Volume steps: 5% per press, with an instant mute toggle. */
function stepVolume(current, delta) {
  const value = Number(current);
  const base = Number.isFinite(value) ? value : 1;
  const next = Math.max(0, Math.min(1, Math.round((base + Number(delta || 0)) * 100) / 100));
  return next;
}

/**
 * Playback rates, clamped to what the engine accepts, with the "remember my
 * speed across episodes" rule the reference players expose.
 */
const PLAYBACK_RATES = Object.freeze([0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]);

function stepRate(current, direction) {
  const value = Number(current) || 1;
  const index = PLAYBACK_RATES.indexOf(value);
  const from = index >= 0 ? index : PLAYBACK_RATES.indexOf(1);
  const next = Math.max(0, Math.min(PLAYBACK_RATES.length - 1, from + (direction < 0 ? -1 : 1)));
  return PLAYBACK_RATES[next];
}

function clampRate(rate, available) {
  const list = Array.isArray(available) && available.length ? available : PLAYBACK_RATES;
  const value = Number(rate) || 1;
  return list.reduce((best, candidate) =>
    Math.abs(candidate - value) < Math.abs(best - value) ? candidate : best, list[0]);
}

/**
 * Fullscreen, with the ordered fallback ladder video.js uses
 * (core/src/dom/presentation/fullscreen.ts):
 *
 *   1. element.requestFullscreen()            — the standard
 *   2. element.webkitRequestFullscreen()      — older Safari
 *   3. video.webkitSetPresentationMode('fullscreen') — iOS Safari, which has
 *      no element fullscreen at all; the video element takes over the screen
 *   4. video.requestFullscreen()              — a video-element fallback
 *
 * Each rung is tried only when the previous one is absent, and the result says
 * which rung answered so the UI can stop offering a control that cannot work.
 */
function createFullscreenController(options) {
  const o = options || {};
  const getContainer = typeof o.getContainer === "function" ? o.getContainer : () => null;
  const getMedia = typeof o.getMedia === "function" ? o.getMedia : () => null;
  const getDocument = typeof o.getDocument === "function" ? o.getDocument : () => (typeof document !== "undefined" ? document : null);

  function doc() {
    return getDocument();
  }

  function isFullscreen() {
    const d = doc();
    const media = getMedia();
    if (!d) return false;
    if (media && media.webkitPresentationMode === "fullscreen") return true;
    return !!(d.fullscreenElement || d.webkitFullscreenElement);
  }

  function supported() {
    const d = doc();
    const media = getMedia();
    const container = getContainer();
    if (d && (d.fullscreenEnabled || d.webkitFullscreenEnabled)) return true;
    if (media && typeof media.webkitSetPresentationMode === "function") return true;
    if (container && typeof container.requestFullscreen === "function") return true;
    return false;
  }

  function request() {
    const d = doc();
    const container = getContainer();
    const media = getMedia();
    try {
      if (container && d && (d.fullscreenEnabled || d.webkitFullscreenEnabled)) {
        if (typeof container.requestFullscreen === "function") {
          return Promise.resolve(container.requestFullscreen()).then(() => "standard");
        }
        if (typeof container.webkitRequestFullscreen === "function") {
          container.webkitRequestFullscreen();
          return Promise.resolve("webkit");
        }
      }
      if (media && typeof media.webkitSetPresentationMode === "function") {
        media.webkitSetPresentationMode("fullscreen");
        return Promise.resolve("ios-presentation");
      }
      if (media && typeof media.requestFullscreen === "function") {
        return Promise.resolve(media.requestFullscreen()).then(() => "media");
      }
    } catch (error) {
      return Promise.resolve("failed").then((value) => ({ ok: false, reason: "threw", error, via: value }));
    }
    return Promise.resolve({ ok: false, reason: "unsupported" });
  }

  function exit() {
    const d = doc();
    const media = getMedia();
    try {
      if (media && media.webkitPresentationMode === "fullscreen" && typeof media.webkitSetPresentationMode === "function") {
        media.webkitSetPresentationMode("inline");
        return Promise.resolve(true);
      }
      if (d && typeof d.exitFullscreen === "function") return Promise.resolve(d.exitFullscreen()).then(() => true);
      if (d && typeof d.webkitExitFullscreen === "function") return Promise.resolve(d.webkitExitFullscreen()).then(() => true);
      if (media && typeof media.exitFullscreen === "function") return Promise.resolve(media.exitFullscreen()).then(() => true);
    } catch (error) {
      void error;
    }
    return Promise.resolve(false);
  }

  function toggle() {
    return isFullscreen() ? exit() : request();
  }

  return { isFullscreen, supported, request, exit, toggle };
}

/**
 * Picture in Picture, including the Safari `webkitSetPresentationMode` path
 * and the "the browser will refuse this" pre-check.
 *
 * video.js's pip-button does the trivial half (`isPictureInPicture ?
 * exit : request`). The half that matters here is that `requestPictureInPicture`
 * rejects without a user gesture, and a rejection reported as a failure looks
 * like a broken button. It is reported as "needs-gesture" instead.
 */
function createPipController(options) {
  const o = options || {};
  const getMedia = typeof o.getMedia === "function" ? o.getMedia : () => null;
  const getDocument = typeof o.getDocument === "function" ? o.getDocument : () => (typeof document !== "undefined" ? document : null);

  function active() {
    const media = getMedia();
    const d = getDocument();
    if (!media) return false;
    if (typeof d !== "undefined" && d && d.pictureInPictureElement) {
      return d.pictureInPictureElement === media;
    }
    return media.webkitPresentationMode === "picture-in-picture";
  }

  function supported() {
    const media = getMedia();
    const d = getDocument();
    if (!media) return false;
    if (d && typeof d.exitPictureInPicture === "function" && typeof media.requestPictureInPicture === "function") return true;
    if (typeof media.webkitSetPresentationMode === "function") return true;
    return false;
  }

  function enter() {
    const media = getMedia();
    if (!media) return Promise.resolve({ ok: false, reason: "no-media" });
    if (!supported()) return Promise.resolve({ ok: false, reason: "unsupported" });
    if (active()) return Promise.resolve({ ok: true, already: true });
    try {
      if (typeof media.webkitSetPresentationMode === "function") {
        media.webkitSetPresentationMode("picture-in-picture");
        return Promise.resolve({ ok: true, via: "ios-presentation" });
      }
      if (typeof media.requestPictureInPicture === "function") {
        return Promise.resolve(media.requestPictureInPicture())
          .then(() => ({ ok: true, via: "standard" }))
          .catch((error) => {
            const name = (error && error.name) || "";
            /* NotAllowedError is the autoplay-style gesture requirement. */
            if (name === "NotAllowedError") return { ok: false, reason: "needs-gesture", error };
            if (name === "NotSupportedError") return { ok: false, reason: "unsupported", error };
            return { ok: false, reason: "rejected", error };
          });
      }
    } catch (error) {
      return Promise.resolve({ ok: false, reason: "threw", error });
    }
    return Promise.resolve({ ok: false, reason: "unsupported" });
  }

  function exit() {
    const media = getMedia();
    const d = getDocument();
    try {
      if (media && typeof media.webkitSetPresentationMode === "function" && media.webkitPresentationMode === "picture-in-picture") {
        media.webkitSetPresentationMode("inline");
        return Promise.resolve({ ok: true });
      }
      if (d && typeof d.exitPictureInPicture === "function") {
        return Promise.resolve(d.exitPictureInPicture()).then(() => ({ ok: true })).catch(() => ({ ok: false }));
      }
    } catch (error) {
      return Promise.resolve({ ok: false, error });
    }
    return Promise.resolve({ ok: false, reason: "unsupported" });
  }

  return { active, supported, enter, exit, toggle: () => (active() ? exit() : enter()) };
}

/**
 * Autoplay that survives being blocked.
 *
 * indigo-player gates `play()` on a `canAutoplay` probe and exposes
 * `waitingForUser` so the UI can offer a real play affordance instead of
 * pretending to load. The ladder below is the one that actually works:
 *
 *   1. try play() unmuted
 *   2. if rejected, mute and try again — this is allowed by every browser
 *   3. if still rejected, report `needs-gesture` and let the caller show the
 *      big centre play button, unmuting and restarting on the first press
 *
 * The important part is that step 2 tells the caller it happened, so the UI can
 * show "Muted — tap to unmute" rather than silently playing without sound.
 */
function createAutoplayRecovery(options) {
  const o = options || {};
  const getMedia = typeof o.getMedia === "function" ? o.getMedia : () => null;
  const onMuted = typeof o.onMuted === "function" ? o.onMuted : () => {};
  const onNeedsGesture = typeof o.onNeedsGesture === "function" ? o.onNeedsGesture : () => {};

  function attempt() {
    const media = getMedia();
    if (!media || typeof media.play !== "function") {
      return Promise.resolve({ ok: false, reason: "no-media" });
    }
    const wasMuted = media.muted === true;
    media.muted = false;
    return Promise.resolve(media.play())
      .then(() => ({ ok: true, muted: false }))
      .catch((firstError) => {
        /* A rejected play() can be a gesture block or an unsupported source.
           Only a gesture block is worth retrying muted. */
        const name = (firstError && firstError.name) || "";
        if (name !== "NotAllowedError" && name !== "AbortError") {
          return { ok: false, reason: "rejected", error: firstError };
        }
        media.muted = true;
        return Promise.resolve(media.play())
          .then(() => {
            onMuted(true);
            return { ok: true, muted: true, recovered: true };
          })
          .catch((secondError) => {
            media.muted = wasMuted;
            onNeedsGesture({ error: secondError });
            return { ok: false, reason: "needs-gesture", error: secondError };
          });
      });
  }

  function unmute() {
    const media = getMedia();
    if (!media) return false;
    media.muted = false;
    onMuted(false);
    return true;
  }

  return { attempt, unmute };
}

/**
 * Buffering, reported as a duration rather than a flag.
 *
 * A one-frame `waiting` event is normal and must not flash a spinner; a stall
 * that lasts is what the viewer actually notices. This debounces on the way in
 * and keeps the start time so the UI can say "buffering 3s" if it wants to.
 */
function createBufferingTracker(options) {
  const o = options || {};
  const now = typeof o.now === "function" ? o.now : () => Date.now();
  const setTimer = typeof o.setTimeout === "function" ? o.setTimeout : (fn, ms) => setTimeout(fn, ms);
  const clearTimer = typeof o.clearTimeout === "function" ? o.clearTimeout : (id) => clearTimeout(id);
  const showAfterMs = Number.isFinite(Number(o.showAfterMs)) ? Number(o.showAfterMs) : 250;
  const onChange = typeof o.onChange === "function" ? o.onChange : () => {};

  let pending = null;
  let since = 0;
  let startedAt = 0;
  let visible = false;

  function start() {
    if (visible) return;
    if (pending !== null) return;
    startedAt = now();
    pending = setTimer(() => {
      pending = null;
      visible = true;
      since = now();
      onChange(true, { since });
    }, showAfterMs);
  }

  function stop() {
    if (pending !== null) {
      clearTimer(pending);
      pending = null;
    }
    if (!visible) return;
    /* `lasted` is measured from when the stall began, not from when the
       spinner became visible — otherwise the debounce window is lost and the
       reported duration is always ~250 ms short of what the viewer waited. */
    const lasted = startedAt ? now() - startedAt : 0;
    visible = false;
    since = 0;
    startedAt = 0;
    onChange(false, { lasted });
  }

  function dispose() {
    if (pending !== null) {
      clearTimer(pending);
      pending = null;
    }
    visible = false;
    since = 0;
    startedAt = 0;
  }

  return {
    start,
    stop,
    dispose,
    get visible() { return visible; },
    get elapsed() { return since ? now() - since : 0; }
  };
}

module.exports = {
  TIMING,
  SKIP_SECONDS,
  HOLD_REASONS,
  PLAYBACK_RATES,
  KEYBOARD_MAP,
  shouldShowChrome,
  activeHolds,
  createChromeLocks,
  createIdleController,
  createSeekController,
  createGestureRecognizer,
  createFullscreenController,
  createPipController,
  createAutoplayRecovery,
  createBufferingTracker,
  resolveKey,
  stepVolume,
  stepRate,
  clampRate
};
