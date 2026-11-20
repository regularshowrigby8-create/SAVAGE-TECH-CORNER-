"use strict";
/**
 * Checks for lib/player-core.js.
 *
 * These are written against the reference behaviour each function was lifted
 * from, so a future change that "simplifies" the idle controller and breaks
 * the pause-stays-visible rule fails here rather than in front of a viewer.
 */

const path = require("path");
const core = require(path.join(__dirname, "..", "lib", "player-core.js"));

let passed = 0;
const failures = [];
/* Async checks are pushed here and awaited before the summary, so a failed
   promise can never be silently ignored. */
const pending = [];
function checkAsync(name, promise, assert, detail) {
  pending.push(Promise.resolve(promise).then(
    (value) => { if (!assert(value)) failures.push(name + (detail ? " — " + detail : "")); else passed += 1; },
    (error) => { failures.push(name + " — threw " + (error && error.message || error)); }
  ));
}

function check(name, condition, detail) {
  if (condition) {
    passed += 1;
  } else {
    failures.push(name + (detail ? " — " + detail : ""));
  }
}

/* ---------- the visibility predicate (video.js computeVisible) ---------- */

check("idle + playing hides the chrome",
  core.shouldShowChrome({ userActive: false, paused: false }) === false);

check("paused keeps the chrome even when idle",
  core.shouldShowChrome({ userActive: false, paused: true }) === true);

check("a held lock keeps the chrome while playing",
  core.shouldShowChrome({ userActive: false, paused: false, holds: new Set(["menu"]) }) === true);

check("an open menu keeps the chrome",
  core.shouldShowChrome({ userActive: false, menuOpen: true }) === true);

check("a terminal error keeps the chrome",
  core.shouldShowChrome({ userActive: false, errored: true }) === true);

check("the end screen keeps the chrome",
  core.shouldShowChrome({ userActive: false, ended: true }) === true);

check("casting keeps the chrome",
  core.shouldShowChrome({ userActive: false, casting: true }) === true);

check("buffering does not hide the chrome from an active viewer",
  core.shouldShowChrome({ userActive: true, buffering: true, paused: false }) === true);

check("forceHide beats a pause",
  core.shouldShowChrome({ paused: true }, { forceHide: true }) === false);

check("forceShow beats everything",
  core.shouldShowChrome({ userActive: false, paused: false }, { forceShow: true }) === true);

/* ---------- holds: refcounted, idempotent (video.js requestControlsLock) ---- */

{
  const changes = [];
  const locks = core.createChromeLocks({ onChange: (s) => changes.push(s.holdCount) });

  const releaseA = locks.acquire("menu");
  const releaseB = locks.acquire("scrub");
  check("two holds are counted separately", locks.count === 2, "count=" + locks.count);

  check("releasing a hold returns true", releaseA() === true);
  check("releasing the same hold twice is a no-op",
    releaseA() === false && locks.count === 1, "count=" + locks.count);

  check("the last release empties the count", releaseB() === true && locks.count === 0);
  check("every change was published", changes.length >= 3, "changes=" + changes.length);
}

/* ---------- the idle controller (plyr 2s / 3s, video.js one timer) ---------- */

function makeClock() {
  let time = 0;
  const timers = new Map();
  let nextId = 1;
  return {
    now: () => time,
    setTimeout: (fn, ms) => {
      const id = nextId++;
      timers.set(id, { at: time + ms, fn });
      return id;
    },
    clearTimeout: (id) => timers.delete(id),
    advance(ms) {
      const target = time + ms;
      for (;;) {
        const due = [...timers.entries()]
          .filter(([, t]) => t.at <= target)
          .sort((a, b) => a[1].at - b[1].at)[0];
        if (!due) break;
        const [id, timer] = due;
        timers.delete(id);
        time = timer.at;
        timer.fn();
      }
      time = target;
    },
    get pending() { return timers.size; }
  };
}

function makeIdle(initialState, clock) {
  const seen = [];
  const state = { ...initialState };
  const idle = core.createIdleController({
    now: clock.now,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    getState: () => state,
    onVisibilityChange: (visible, meta) => seen.push({ visible, ...meta })
  });
  return { idle, seen, state };
}

{
  const clock = makeClock();
  const { idle, seen, state } = makeIdle({ paused: false }, clock);
  idle.reset(true);
  check("desktop idle window is plyr's 2000ms", idle.deadlineIn === core.TIMING.IDLE_MS,
    "deadlineIn=" + idle.deadlineIn);

  clock.advance(1999);
  check("chrome is still up one millisecond before the deadline",
    seen[seen.length - 1].visible === true);

  clock.advance(2);
  check("chrome hides once the deadline passes",
    seen[seen.length - 1].visible === false, "last=" + JSON.stringify(seen[seen.length - 1]));

  state.paused = true;
  idle.activate();
  clock.advance(5000);
  check("a paused film never hides the chrome",
    seen[seen.length - 1].visible === true);
}

{
  const clock = makeClock();
  const { idle, seen } = makeIdle({ paused: false }, clock);
  idle.reset(true);
  idle.setTouchMode(true);
  idle.activate();
  check("touch idle window is plyr's 3000ms", idle.deadlineIn === core.TIMING.IDLE_TOUCH_MS,
    "deadlineIn=" + idle.deadlineIn);
  clock.advance(2500);
  check("touch chrome is still up at 2.5s", seen[seen.length - 1].visible === true);
  clock.advance(600);
  check("touch chrome hides after 3s", seen[seen.length - 1].visible === false);
}

{
  const clock = makeClock();
  const { idle, seen } = makeIdle({ paused: false }, clock);
  idle.reset(true);
  /* Every pointer move re-arms rather than stacking timers. */
  for (let i = 0; i < 5; i += 1) {
    clock.advance(1000);
    idle.onPointerMove("mouse");
  }
  check("a mouse move keeps the chrome alive indefinitely",
    seen[seen.length - 1].visible === true);
  /* The old code accumulated idle and could never recover; this must. */
  clock.advance(2100);
  check("and it still hides after the last move", seen[seen.length - 1].visible === false);
  idle.onPointerMove("mouse");
  check("and comes straight back", seen[seen.length - 1].visible === true);
}

{
  const clock = makeClock();
  const { idle, seen } = makeIdle({ paused: false }, clock);
  idle.reset(true);
  idle.noteTouch();
  idle.onPointerLeave({ pointerType: "mouse" });
  check("a synthetic mouseleave after a touch is ignored",
    seen[seen.length - 1].visible === true, "last=" + JSON.stringify(seen[seen.length - 1]));

  idle.noteCaptureRelease();
  idle.onPointerLeave({ pointerType: "mouse", insideOnCaptureRelease: true });
  check("the Safari capture-release mouseleave is ignored",
    seen[seen.length - 1].visible === true);
}

{
  const clock = makeClock();
  const { idle, seen } = makeIdle({ paused: false }, clock);
  idle.reset(true);
  idle.onPointerMove("mouse");
  check("a real mouseleave hides the chrome",
    (idle.onPointerLeave({ pointerType: "mouse" }), seen[seen.length - 1].visible === false));
}

{
  const clock = makeClock();
  const { idle, seen } = makeIdle({ paused: false, menuOpen: true }, clock);
  idle.reset(true);
  idle.activate();
  clock.advance(4000);
  check("an open menu holds the chrome past the deadline",
    seen[seen.length - 1].visible === true);
}

/* ---------- seek controller ---------- */

function makeMedia(props) {
  return {
    currentTime: 0, duration: 0, readyState: 0, paused: false,
    ...(props || {})
  };
}

{
  const media = makeMedia({ duration: 0, readyState: 0 });
  const seek = core.createSeekController({ getMedia: () => media });
  check("seeking before metadata is refused, not silently lost",
    seek.seekBy(30).ok === false);
  check("the refusal names the reason", seek.seekBy(30).reason === "not-ready");
}

{
  const media = makeMedia({ duration: 7200, readyState: 4, currentTime: 100 });
  const seek = core.createSeekController({ getMedia: () => media });
  const result = seek.seekBy(10);
  check("a forward skip lands exactly 10s later",
    result.ok === true && result.to === 110, JSON.stringify(result));

  media.currentTime = 5;
  check("a rewind past zero clamps to zero", seek.seekBy(-30).to === 0);

  media.currentTime = 7199;
  const clamped = seek.seekBy(30);
  check("a skip past the end stops just short of the duration",
    clamped.to === 7199.95, "to=" + clamped.to);

  check("seek to a fraction is clamped", seek.seekTo(-5).to === 0);

  media.currentTime = 300;
  const right = seek.seekFromTap(900, 1000);
  check("a tap on the right half goes forward", right.direction === "forward" && right.to === 310);
  media.currentTime = 300;
  const left = seek.seekFromTap(100, 1000);
  check("a tap on the left half goes back", left.direction === "back" && left.to === 290);
}

/* ---------- gestures ---------- */

{
  const clock = makeClock();
  const events = [];
  const gestures = core.createGestureRecognizer({
    now: clock.now,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    onTap: () => events.push("tap"),
    onDoubleTap: () => events.push("doubletap"),
    onDragStart: () => events.push("dragstart"),
    onDragEnd: () => events.push("dragend")
  });

  gestures.pointerDown(100, 100);
  clock.advance(50);
  gestures.pointerUp(100, 100);
  clock.advance(core.TIMING.DOUBLE_TAP_MS + 10);
  check("a lone tap fires after the double-tap window", events.join() === "tap", events.join());

  events.length = 0;
  gestures.pointerDown(100, 100);
  clock.advance(40);
  gestures.pointerUp(100, 100);
  clock.advance(80);
  gestures.pointerDown(100, 100);
  clock.advance(40);
  gestures.pointerUp(100, 100);
  clock.advance(core.TIMING.DOUBLE_TAP_MS + 10);
  check("two quick taps produce one doubletap and no stray tap",
    events.join() === "doubletap", events.join());

  events.length = 0;
  gestures.pointerDown(100, 100);
  gestures.pointerMove(160, 102);
  gestures.pointerMove(240, 104);
  gestures.pointerUp(240, 104);
  clock.advance(core.TIMING.DOUBLE_TAP_MS + 10);
  check("a horizontal drag never becomes a tap",
    events.join() === "dragstart,dragend", events.join());
}

/* ---------- keyboard + stepping ---------- */

check("space toggles play",
  core.resolveKey(" ").action === "toggle-play");
check("j and l seek like arrows",
  core.resolveKey("j").action === "seek-back" && core.resolveKey("l").action === "seek-forward");
check("digits are percentage jumps",
  core.resolveKey("5").action === "seek-percent" && core.resolveKey("5").value === 50);
check("an unmapped key is ignored", core.resolveKey("q") === null);
check("volume steps clamp at 1", core.stepVolume(0.98, 0.05) === 1);
check("volume steps clamp at 0", core.stepVolume(0.02, -0.05) === 0);
check("rate steps up one rung", core.stepRate(1, 1) === 1.25);
check("rate steps down one rung", core.stepRate(1, -1) === 0.75);
check("an unsupported rate snaps to the nearest offered one",
  core.clampRate(1.3, [0.5, 1, 2]) === 1);

/* ---------- fullscreen ladder (video.js presentation/fullscreen.ts) ------- */

{
  const calls = [];
  const container = {
    requestFullscreen: () => { calls.push("standard"); return Promise.resolve(); }
  };
  const doc = { fullscreenEnabled: true, fullscreenElement: null };
  const fs = core.createFullscreenController({
    getContainer: () => container, getMedia: () => null, getDocument: () => doc
  });
  check("fullscreen prefers the standard API", fs.supported() === true);
  checkAsync("the standard rung is used when present", fs.request(), (via) => via === "standard");
}

{
  /* iOS: no element fullscreen; the video element takes the screen. */
  const calls = [];
  const media = { webkitSetPresentationMode: (mode) => calls.push(mode), webkitPresentationMode: "inline" };
  const fs = core.createFullscreenController({
    getContainer: () => ({}),
    getMedia: () => media,
    getDocument: () => ({ fullscreenEnabled: false })
  });
  check("iOS presentation mode is detected as supported", fs.supported() === true);
  fs.request();
  check("iOS uses webkitSetPresentationMode", calls[0] === "fullscreen", calls.join());
  media.webkitPresentationMode = "fullscreen";
  check("iOS fullscreen is reported", fs.isFullscreen() === true);
  fs.exit();
  check("iOS exit switches back inline", calls[calls.length - 1] === "inline", calls.join());
}

/* ---------- PiP (video.js pip-button) ---------- */

{
  const media = {
    requestPictureInPicture: () => Promise.reject({ name: "NotAllowedError" })
  };
  const doc = { exitPictureInPicture: () => Promise.resolve(), pictureInPictureElement: null };
  const pip = core.createPipController({ getMedia: () => media, getDocument: () => doc });
  check("PiP is supported when both halves exist", pip.supported() === true);
  checkAsync("a gesture-blocked PiP reports needs-gesture, not a failure",
    pip.enter(), (result) => result.ok === false && result.reason === "needs-gesture");
}

{
  const media = { requestPictureInPicture: () => Promise.reject({ name: "NotSupportedError" }) };
  const doc = { exitPictureInPicture: () => Promise.resolve() };
  const pip = core.createPipController({ getMedia: () => media, getDocument: () => doc });
  checkAsync("an unsupported PiP source says so",
    pip.enter(), (result) => result.reason === "unsupported");
}

/* ---------- autoplay recovery (indigo-player canAutoplay) ---------- */

{
  const media = {
    muted: false,
    play() {
      if (!this.muted) return Promise.reject({ name: "NotAllowedError" });
      return Promise.resolve();
    }
  };
  let mutedNotified = null;
  const auto = core.createAutoplayRecovery({
    getMedia: () => media,
    onMuted: (value) => { mutedNotified = value; }
  });
  checkAsync("a blocked autoplay retries muted",
    auto.attempt(), (result) => result.ok === true && result.muted === true);
  checkAsync("the caller is told it was muted",
    auto.attempt(), () => mutedNotified === true);
}

{
  const media = {
    muted: false,
    play() { return Promise.reject({ name: "NotAllowedError" }); }
  };
  let gesture = false;
  const auto = core.createAutoplayRecovery({
    getMedia: () => media,
    onNeedsGesture: () => { gesture = true; }
  });
  checkAsync("when even muted playback is blocked the caller is told to show play",
    auto.attempt(), (result) => result.reason === "needs-gesture" && gesture === true);
}

{
  const media = { muted: false, play() { return Promise.reject({ name: "NotSupportedError" }); } };
  const auto = core.createAutoplayRecovery({ getMedia: () => media });
  checkAsync("a genuinely unsupported source is not retried muted",
    auto.attempt(), (result) => result.reason === "rejected");
}

/* ---------- buffering (debounced, duration-aware) ---------- */

{
  const clock = makeClock();
  const changes = [];
  const buffer = core.createBufferingTracker({
    now: clock.now, setTimeout: clock.setTimeout, clearTimeout: clock.clearTimeout,
    showAfterMs: 250,
    onChange: (visible, meta) => changes.push({ visible, ...meta })
  });
  buffer.start();
  clock.advance(100);
  buffer.stop();
  check("a blip shorter than the debounce never shows a spinner", changes.length === 0);

  buffer.start();
  clock.advance(300);
  check("a real stall shows the spinner", changes.length === 1 && changes[0].visible === true);
  clock.advance(2000);
  buffer.stop();
  /* The stall began at t=0 and ended at t=2300; `lasted` counts from the start
     of the stall, including the debounce window. */
  check("the stall duration is reported from the start of the stall",
    changes[changes.length - 1].visible === false && changes[changes.length - 1].lasted === 2300,
    JSON.stringify(changes[changes.length - 1]));
}

/* Settle every async check, then report. */
Promise.all(pending).then(() => {
  console.log(passed + " player-core checks passed");
  if (failures.length) {
    console.log("\nFAILURES:");
    for (const failure of failures) console.log("  ✗ " + failure);
    process.exit(1);
  }
});
