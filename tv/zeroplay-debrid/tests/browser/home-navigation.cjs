/* Real-browser acceptance checks. Requires the repository's Playwright dev dependency.
 * Run: node tests/browser/home-navigation.cjs
 * Optionally set CHROMIUM_EXECUTABLE_PATH and CHROMIUM_LIBRARY_PATH for a system browser.
 * All network responses are deterministic fixtures; no external services/keys are used.
 */
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const http = require("node:http");
let chromium;
try {
  ({ chromium } = require("playwright"));
} catch (error) {
  console.error(
    "This suite needs a real browser. Install it first:\n" +
      "  npm install\n" +
      "  npx playwright install --with-deps chromium\n" +
      "On CI this runs in .github/workflows/zeroplay-tv-browser.yml.",
  );
  process.exit(1);
}
const html = fs.readFileSync(path.join(__dirname, "../../index.html"), "utf8");
const art =
  '<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="900"><defs><linearGradient id="g"><stop stop-color="#132b3f"/><stop offset="1" stop-color="#8b6944"/></linearGradient></defs><path fill="url(#g)" d="M0 0h1600v900H0z"/><circle cx="1100" cy="340" r="210" fill="#c6a66b" opacity=".3"/></svg>';
const fixture = (type = "movie", id = 101) => ({
  id,
  media_type: type,
  title: type === "movie" ? `Test Film ${id}` : undefined,
  name: type === "tv" ? `Test Series ${id}` : undefined,
  release_date: "2025-04-02",
  first_air_date: "2025-04-02",
  vote_average: 8.2,
  genre_ids: [28, 18],
  genres: [{ id: 28, name: "Action" }],
  overview: "A featured title for deterministic Home and navigation acceptance checks.",
  poster_path: "/poster.jpg",
  backdrop_path: "/backdrop.jpg",
  imdb_id: "tt1234567",
  external_ids: { imdb_id: "tt1234567" },
  runtime: 110,
  videos: { results: [] },
  credits: { cast: [] },
  similar: { results: [] },
});
let passed = 0;
function check(value, label) {
  assert(value, label);
  console.log("PASS " + label);
  passed++;
}
async function main() {
  const server = http.createServer((req, res) => {
    res.setHeader("Content-Type", "text/html");
    res.end(html);
  });
  await new Promise((resolve) => server.listen(0, "0.0.0.0", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  let browser;
  try {
    const launch = { headless: true };
    if (process.env.CHROMIUM_EXECUTABLE_PATH) {
      launch.executablePath = process.env.CHROMIUM_EXECUTABLE_PATH;
      launch.args = ["--no-sandbox", "--disable-dev-shm-usage", "--no-zygote"];
      if (process.env.CHROMIUM_LIBRARY_PATH)
        launch.env = { ...process.env, LD_LIBRARY_PATH: process.env.CHROMIUM_LIBRARY_PATH };
    }
    browser = await chromium.launch(launch);
    async function open({
      progress = false,
      android = true,
      width = 1920,
      height = 1080,
      logo = false,
      failed = false,
    } = {}) {
      const context = await browser.newContext({
        viewport: { width, height },
        reducedMotion: "reduce",
      });
      const page = await context.newPage();
      const errors = [];
      page.on("pageerror", (e) => errors.push(e.message));
      page.on("console", (m) => {
        if (m.type() === "error") errors.push(m.text());
      });
      const requests = [];
      await page.route("**/*", async (route) => {
        const req = route.request(),
          url = new URL(req.url());
        if (url.origin === base && req.resourceType() === "document") return route.continue();
        if (req.resourceType() === "image")
          return route.fulfill({ contentType: "image/svg+xml", body: art });
        if (url.pathname === "/api/tmdb") {
          const endpoint = url.searchParams.get("path");
          requests.push(endpoint);
          // Yield long enough to observe all original loading slots before fill replaces them.
          await new Promise((resolve) => setTimeout(resolve, 80));
          if (failed) return route.fulfill({ json: { results: [] } });
          const item = fixture(endpoint.startsWith("/tv") ? "tv" : "movie");
          if (logo) item.logo_url = base + "/logo.svg";
          const data = /\/(movie|tv)\/\d+$/.test(endpoint)
            ? item
            : {
                results: [item, fixture("movie", 102), fixture("tv", 103)],
                total_pages: 1,
                page: 1,
              };
          return route.fulfill({ json: data });
        }
        if (req.resourceType() === "document")
          return route.fulfill({
            contentType: "text/html",
            body: "<!doctype html><title>Fixture player</title>",
          });
        return route.fulfill({ json: {} });
      });
      await page.addInitScript(
        ({ progress, item }) => {
          localStorage.setItem(
            "zp_rd_settings_v1",
            JSON.stringify({
              discovery: { autoStart: false },
              feed: { auto: false, healthAuto: false, addonsAuto: false },
              stream: { trailerPreviews: false, dnsWarm: false },
              artwork: { enabled: false },
              ui: { reduceMotion: true, androidTvLaunchFocus: false },
            }),
          );
          localStorage.setItem(
            "zp_rd_progress_v1",
            JSON.stringify(
              progress
                ? {
                    "movie:101": {
                      ...item,
                      type: "movie",
                      percent: 35,
                      timestamp: 1200,
                      duration: 7200,
                      updatedAt: Date.now(),
                    },
                  }
                : {},
            ),
          );
          localStorage.setItem(
            "zp_rd_watchlist_v1",
            JSON.stringify([{ ...item, type: "movie", year: "2025", rating: "8.2" }]),
          );
        },
        { progress, item: fixture() },
      );
      await page.goto(`${base}/?${android ? "androidtv=1" : ""}#/home`);
      await page.waitForFunction(
        () => document.querySelector("#heroPlay") || !document.querySelector("#heroSlot .skel"),
      );
      if (!failed)
        await page.waitForFunction(
          () => document.querySelectorAll("#view .poster-card").length >= 24,
        );
      return { context, page, errors, requests };
    }
    for (const progress of [false, true]) {
      const { context, page, errors, requests } = await open({ progress });
      const initialProgress = await page.evaluate(() => JSON.parse(JSON.stringify(progress)));
      check(
        (await page.locator(".continue-rail").count()) === (progress ? 1 : 0),
        `Continue Watching appears ${progress ? "exactly once with progress" : "zero times without progress"}`,
      );
      if (progress) {
        const resume = await page.locator(".continue-rail").boundingBox(),
          hero = await page.locator("#heroSlot .hero").boundingBox();
        check(
          resume.y + resume.height <= hero.y,
          "Continue Watching is before the hero, without overlap",
        );
      }
      check(
        await page.locator(".hero-bg").evaluate((img) => img.complete && img.naturalWidth > 0),
        "featured artwork loads",
      );
      const endpoints = [
        "/trending/all/day",
        "/movie/popular",
        "/tv/popular",
        "/discover/movie",
        "/movie/top_rated",
        "/movie/now_playing",
      ];
      check(
        endpoints.every((p) => requests.includes(p)) &&
          requests.filter((p) => p === "/discover/movie").length === 3,
        "all eight Home rail requests load through the original fill helper",
      );
      check(
        (await page.locator("#view .rail").count()) === (progress ? 11 : 10),
        "all catalogue/category/watchlist rails render once",
      );
      for (const id of ["heroPlay", "heroDetails", "heroServers", "heroList"])
        check(await page.locator("#" + id).isVisible(), `${id} is visible on Android TV`);
      await page.evaluate(() => {
        window.actionCalls = [];
        window.originalDetail = openDetail;
        window.originalPlayer = openPlayer;
        window.originalServers = showStreamsModal;
        openPlayer = (item, options) => actionCalls.push({ kind: "play", item, options });
        openDetail = (type, id) => actionCalls.push({ kind: "details", type, id });
        showStreamsModal = (ctx) => actionCalls.push({ kind: "servers", ctx });
      });
      await page.locator("#heroPlay").click();
      await page.locator("#heroDetails").click();
      await page.locator("#heroServers").click();
      const actions = await page.evaluate(() => actionCalls);
      check(
        actions.length === 3 &&
          actions[0].item.id === 101 &&
          actions[1].id === 101 &&
          actions[2].ctx.identity.tmdbId === 101,
        "Play, Details and Servers each dispatch once with the featured title identity",
      );
      await page.locator("#heroList").click();
      check(
        !(await page.evaluate(() =>
          JSON.parse(localStorage.getItem("zp_rd_watchlist_v1")).some((x) => x.id === 101),
        )),
        "Watchlist action removes the featured title",
      );
      await page.locator("#heroList").click();
      check(
        await page.evaluate(() =>
          JSON.parse(localStorage.getItem("zp_rd_watchlist_v1")).some((x) => x.id === 101),
        ),
        "Watchlist action adds the featured title",
      );
      await page.keyboard.press("Tab");
      await page.locator(".poster-card").first().focus();
      const focus = await page
        .locator(".poster-card")
        .first()
        .evaluate((el) => ({
          after: getComputedStyle(el.querySelector(".poster-wrap"), "::after").content,
          before: getComputedStyle(el.querySelector(".poster-wrap"), "::before").content,
          shadow: getComputedStyle(el.querySelector(".poster-wrap")).boxShadow,
          outline: getComputedStyle(el).outlineWidth,
        }));
      check(
        !/[▶►⏵]/.test(focus.after + focus.before) &&
          (focus.shadow !== "none" || focus.outline !== "0px"),
        "focused poster has a clear focus indicator without a false Play glyph",
      );
      await page.keyboard.press("Enter");
      check(
        (await page.evaluate(() => actionCalls)).at(-1).kind === "details",
        "Enter on a focused poster opens Details, not playback",
      );
      // Exercise the real original detail renderer separately from dispatch spies.
      await page.evaluate(() => {
        openDetail = originalDetail;
        return openDetail("movie", 101);
      });
      check(
        await page.locator("#modalRoot .modal").isVisible(),
        "real Details action opens the title modal",
      );
      await page.keyboard.press("Escape");
      check((await page.locator("#modalRoot .modal").count()) === 0, "Back closes Details");
      await page.evaluate(() => {
        openPlayer = originalPlayer;
        showStreamsModal = originalServers;
      });
      await page.locator("#heroServers").click();
      await page.locator("#streamsRoot .modal").waitFor({ state: "visible" });
      check(true, "real Servers action opens the title-specific source picker");
      await page.keyboard.press("Escape");
      check((await page.locator("#streamsRoot .modal").count()) === 0, "Back closes Servers");
      await page.locator("#heroPlay").click();
      await page.locator("#playerOverlay").waitFor({ state: "visible" });
      check(
        await page.locator("#playerOverlay").isVisible(),
        "real Play action opens the existing player",
      );
      await page.evaluate(() => closePlayer());
      check((await page.locator("#playerOverlay").count()) === 0, "existing player closes cleanly");
      // Opening the real player legitimately writes progress; reset the fixture for later Home assertions.
      await page.evaluate((initial) => {
        progress = initial;
        saveProgress();
      }, initialProgress);
      await page.evaluate(() => {
        window.routeCalls = [];
        window.focusEvents = [];
        document.addEventListener("focusin", (e) =>
          focusEvents.push({ id: e.target.id, cls: e.target.className, time: performance.now() }),
        );
        const original = goRoute;
        goRoute = (route) => {
          routeCalls.push(route);
          return original(route);
        };
      });
      for (const container of [".zp-sidebar", "#menuDrawer"]) {
        for (const route of [
          "movies",
          "series",
          "categories",
          "library",
          "sources",
          "downloads",
          "settings",
          "home",
        ]) {
          if (container === "#menuDrawer") {
            await page.locator("#btnMenu").click();
            await page
              .waitForFunction(() => document.activeElement.id === "btnCloseMenu", null, {
                timeout: 2000,
              })
              .catch(async (error) => {
                console.log(
                  "MENU DEBUG",
                  await page.evaluate(() => ({
                    active: document.activeElement.outerHTML.slice(0, 160),
                    drawer: document.querySelector("#menuDrawer").className,
                    hidden: document.querySelector("#menuDrawer").getAttribute("aria-hidden"),
                    events: focusEvents.slice(-8),
                    route: location.hash,
                    player: !!playerState,
                    detail: !!detailState,
                    streams: !!streamsCtx,
                  })),
                );
                throw error;
              });
          }
          const before = await page.evaluate(() => routeCalls.length);
          await page.locator(`${container} [data-menu-route="${route}"]`).click();
          await page.waitForURL(`**#/${route}`);
          check(
            (await page.evaluate(() => routeCalls.length)) === before + 1,
            `${container} ${route} activation navigates once`,
          );
          await page.waitForFunction(
            (route) =>
              document
                .querySelector(`.zp-sidebar [data-menu-route="${route}"]`)
                .getAttribute("aria-current") === "page",
            route,
          );
          check(true, `${route} has the correct active-route state`);
        }
      }
      for (const container of [".zp-sidebar", "#menuDrawer"]) {
        if (container === "#menuDrawer") {
          await page.locator("#btnMenu").click();
          await page
            .waitForFunction(() => document.activeElement.id === "btnCloseMenu", null, {
              timeout: 2000,
            })
            .catch(async (error) => {
              console.log(
                "MENU DEBUG",
                await page.evaluate(() => ({
                  active: document.activeElement.outerHTML.slice(0, 160),
                  drawer: document.querySelector("#menuDrawer").className,
                  hidden: document.querySelector("#menuDrawer").getAttribute("aria-hidden"),
                  events: focusEvents.slice(-8),
                  route: location.hash,
                  player: !!playerState,
                  detail: !!detailState,
                  streams: !!streamsCtx,
                })),
              );
              throw error;
            });
        }
        const before = await page.evaluate(() => routeCalls.length);
        await page.locator(`${container} [data-menu-route="home"]`).focus();
        await page.keyboard.press("Enter");
        check(
          (await page.evaluate(() => routeCalls.length)) === before + 1,
          `${container} remote Enter also activates the current route exactly once`,
        );
        await page.locator("#heroPlay").waitFor({ state: "visible" });
        check(
          (await page.locator(".continue-rail").count()) === (progress ? 1 : 0),
          "repeat Home activation does not duplicate Continue Watching",
        );
      }
      for (const trigger of ["#btnMenu", ".zp-sidebar-more"]) {
        await page.locator(trigger).click();
        check(
          (await page.locator("#menuDrawer").getAttribute("aria-hidden")) === "false",
          `${trigger} opens the menu`,
        );
        await page.keyboard.press("Escape");
        check(
          (await page.locator("#menuDrawer").getAttribute("aria-hidden")) === "true" &&
            (await page.locator(trigger).evaluate((el) => el === document.activeElement)),
          "Back closes the menu and restores focus to its trigger",
        );
      }
      await page.locator("#topQuery").fill("Test Film & series");
      await page.locator("#topQuery").press("Enter");
      await page.waitForURL("**#/search?q=Test%20Film%20%26%20series");
      check(
        (await page.locator("#searchInput").inputValue()) === "Test Film & series",
        "search submission navigates with an encoded query and renders search",
      );
      check(
        errors.length === 0,
        "browser console and uncaught errors are clean: " + errors.join("; "),
      );
      await context.close();
    }
    for (const [width, height, android] of [
      [1920, 1080, true],
      [1280, 720, true],
      [980, 720, true],
      [960, 720, true],
      [1920, 1080, false],
      [390, 844, false],
    ]) {
      const { context, page, errors } = await open({ progress: true, width, height, android });
      const layout = await page.evaluate(() => {
        const box = (s) => {
          const r = document.querySelector(s).getBoundingClientRect();
          return {
            x: r.x,
            y: r.y,
            right: r.right,
            bottom: r.bottom,
            width: r.width,
            height: r.height,
          };
        };
        return {
          sidebar: box(".zp-sidebar"),
          topbar: box(".topbar"),
          home: box(".zp-home"),
          hero: box(".hero"),
          pageWidth: document.documentElement.scrollWidth,
          windowWidth: innerWidth,
        };
      });
      if (width >= 980)
        check(
          layout.topbar.x >= layout.sidebar.right && layout.home.x >= layout.sidebar.right,
          `${width}×${height}: sidebar never overlaps top bar or Home`,
        );
      else
        check(
          layout.sidebar.width === 0 && layout.topbar.x === 0 && layout.home.x === 0,
          `${width}×${height}: narrow layout has no invisible-sidebar offset`,
        );
      check(
        layout.home.y >= layout.topbar.bottom && layout.pageWidth <= width,
        `${width}×${height}: Home clears top bar and has no horizontal page overflow`,
      );
      check(errors.length === 0, `${width}×${height}: console stays clean`);
      if (process.env.TV_SCREENSHOT_DIR) {
        fs.mkdirSync(process.env.TV_SCREENSHOT_DIR, { recursive: true });
        await page.screenshot({
          path: path.join(
            process.env.TV_SCREENSHOT_DIR,
            `home-${width}-${android ? "tv" : "desktop"}.png`,
          ),
        });
      }
      await context.close();
    }
    /* ---- the defects reported from the sofa, measured in a real browser ---- */
    {
      const { context, page, errors } = await open({ progress: true });

      /* D1: a focused card must not wear a white ring. The accent is #e6b355
         (230,179,85); anything near-white here is the old bug. */
      await page.locator("#view .poster-card").first().focus();
      const ring = await page.evaluate(() => {
        const card = document.querySelector("#view .poster-card");
        const wrap = card.querySelector(".poster-wrap") || card;
        const cs = getComputedStyle(card);
        const ws = getComputedStyle(wrap);
        return {
          outlineColor: cs.outlineColor,
          outlineWidth: parseFloat(cs.outlineWidth) || 0,
          shadow: ws.boxShadow,
        };
      });
      const parsed = (ring.outlineColor.match(/[\d.]+/g) || []).map(Number);
      const isWhite = parsed.length >= 3 && parsed[0] > 245 && parsed[1] > 245 && parsed[2] > 245;
      check(!isWhite, `focused card ring is not white (${ring.outlineColor})`);
      check(
        !/rgba?\(255,\s*255,\s*255,\s*0?\.[5-9]/.test(ring.shadow),
        "focused card elevation has no white halo",
      );

      /* D2/D3: the D-pad moves through shelves and never lands in the sidebar. */
      await page.locator("#view .poster-card").first().focus();
      const trail = [];
      for (const key of ["ArrowRight", "ArrowRight", "ArrowDown", "ArrowDown", "ArrowUp"]) {
        await page.keyboard.press(key);
        trail.push(
          await page.evaluate(() => {
            const el = document.activeElement;
            const rail = el && el.closest && el.closest(".rail-track");
            return {
              inSidebar: !!(el && el.closest && el.closest(".zp-sidebar")),
              tag: el ? el.tagName : "",
              id: el ? el.id : "",
              rail: rail ? [...document.querySelectorAll(".rail-track")].indexOf(rail) : -1,
              card: !!(el && el.classList && el.classList.contains("poster-card")),
              interactive: !!(el && el.matches && el.matches('button,a[href],input,select,textarea,[tabindex]:not([tabindex="-1"])')),
              isBody: el === document.body,
            };
          }),
        );
      }
      check(
        trail.every((stop) => !stop.inSidebar),
        "no arrow key lands focus in the sidebar: " + JSON.stringify(trail),
      );
      check(
        trail.every((stop) => stop.interactive && !stop.isBody),
        "every arrow press leaves focus on a real control: " + JSON.stringify(trail),
      );
      check(
        trail[0].rail === trail[1].rail && trail[0].rail >= 0,
        "left/right stays inside the shelf it started on",
      );
      check(trail[2].rail !== trail[0].rail, "down moves to a different shelf");
      check(errors.length === 0, "no console errors while driving the D-pad");

      /* D5: the player's bars must leave the screen while a film plays. */
      await page.evaluate(() => {
        openPlayer({ id: 101, type: "movie", title: "Test Film 101" });
        playerState = playerState || {};
        if (playerState) {
          playerState.trailer = false;
          playerState.playback = Object.assign({}, playerState.playback, {
            paused: false,
            buffering: false,
            currentTime: 30,
            duration: 7200,
          });
        }
        showPlayerChrome(false);
      });
      await page.locator("#playerOverlay").waitFor({ state: "visible" });
      const hid = await page
        .waitForFunction(
          () => document.getElementById("playerOverlay").classList.contains("chrome-hidden"),
          null,
          { timeout: 8000 },
        )
        .then(() => true)
        .catch(() => false);
      check(hid, "player bars hide on their own while playback continues");
      await page.evaluate(() => closePlayer && closePlayer());
      await context.close();
    }

    const { context, page, errors } = await open({ logo: true });
    check(
      (await page.locator("#heroLogo").isVisible()) &&
        (await page.locator("#heroLogo").evaluate((el) => getComputedStyle(el).opacity === "1")),
      "original logo load handler produces a visible title logo",
    );
    check(
      !(await page.locator("#heroTitle").isVisible()),
      "title text is hidden only after the featured logo loads",
    );
    check(errors.length === 0, "logo path has no console errors");
    await context.close();
    console.log(`\n${passed} browser acceptance checks passed`);
    /* The count is published as an annotation so a run can be audited without
       downloading logs: zero checks would otherwise look like success. */
    console.log(`::notice title=Browser acceptance::${passed} real-browser checks passed`);
  } finally {
    if (browser) await browser.close();
    await new Promise((resolve) => server.close(resolve));
  }
}
main().catch((error) => {
  const message = String((error && error.message) || error);
  console.error(error);
  /* A failing acceptance check must be readable from the run itself — the
     GitHub log download needs network the reviewer may not have, but
     annotations are available through the API. */
  const annotation = message.replace(/[\r\n]+/g, " ").slice(0, 900);
  console.log(`::error title=Browser acceptance failed::${annotation}`);
  process.exit(1);
});
