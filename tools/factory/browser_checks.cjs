/** Real Chromium checks on owned files; no native bridge or external target is faked. */
const fs = require("node:fs");
const path = require("node:path");
const assert = require("node:assert/strict");
const { chromium } = require("playwright");
const mode = process.argv[2];
const out = path.resolve(process.argv[3]);
const assets = path.resolve(__dirname, "../../app/src/main/assets");
const report = {
  checks: [],
  violations: [],
  pageErrors: [],
  externalRequestsBlocked: [],
  nativeWebViewVerified: false,
};

async function main() {
  assert.ok(["smoke", "accessibility"].includes(mode));
  const browser = await chromium.launch({ headless: true });
  try {
    const context = await browser.newContext({
      viewport: { width: 390, height: 844 },
      reducedMotion: "reduce",
    });
    await context.route("**/*", async (route) => {
      const url = new URL(route.request().url());
      if (url.origin !== "https://factory.invalid") {
        report.externalRequestsBlocked.push(url.origin);
        await route.abort();
        return;
      }
      const name = decodeURIComponent(url.pathname);
      const file =
        name === "/factory.html"
          ? path.join(out, "factory.html")
          : path.resolve(assets, "." + name);
      if (name !== "/factory.html" && !file.startsWith(assets + path.sep)) {
        await route.abort();
        return;
      }
      if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
        await route.fulfill({ status: 404, body: "Missing local asset" });
        return;
      }
      await route.fulfill({ path: file });
    });
    const page = await context.newPage();
    page.setDefaultTimeout(10000);
    page.on("pageerror", (error) => report.pageErrors.push(error.message));
    if (mode === "smoke") {
      await page.goto("https://factory.invalid/factory.html");
      const total = await page.locator("tbody tr").count();
      await page.locator("#search").fill("pipdeptree");
      assert.equal(await page.locator("tbody tr:visible").count(), 1);
      await page.locator("#search").fill("no-such-factory-engine");
      assert.equal(await page.locator("tbody tr:visible").count(), 0);
      await page.locator("#reset").click();
      assert.equal(await page.locator("tbody tr:visible").count(), total);
      await page.locator("#state").selectOption("FAIL");
      assert.ok((await page.locator("tbody tr:visible").count()) > 0);
      assert.ok((await page.locator("tbody tr:visible").count()) < total);
      assert.ok(
        await page
          .locator("tbody tr:visible")
          .evaluateAll((rows) =>
            rows.every((row) => row.dataset.state === "FAIL"),
          ),
      );
      await page.locator("#reset").click();
      report.checks.push(
        "Dashboard search, no-match, status filter and reset change visible rows",
      );
      await page.screenshot({
        path: path.join(out, "factory.png"),
        fullPage: true,
      });
      await page.goto(
        "https://factory.invalid/error_page.html?reason=offline&url=https%3A%2F%2Fexample.invalid",
      );
      assert.equal(await page.locator("#retryBtn").isEnabled(), true);
      await page.screenshot({ path: path.join(out, "retry-before.png") });
      await page.locator("#retryBtn").click({ noWaitAfter: true });
      assert.equal(await page.locator("#retryBtn").isDisabled(), true);
      assert.equal(
        await page.locator("#retryBtn").textContent(),
        "Reconnecting…",
      );
      await page.screenshot({ path: path.join(out, "retry-after.png") });
      await page.reload();
      assert.equal(await page.locator("#retryBtn").isEnabled(), true);
      report.checks.push(
        "Actual error-page retry disables and labels itself; reload restores it; native dispatch unverified",
      );
    } else {
      for (const file of ["error_page.html", "chat.html", "start_page.html"]) {
        await page.goto("https://factory.invalid/" + file);
        await page.addScriptTag({
          path: require.resolve("axe-core/axe.min.js"),
        });
        const result = await page.evaluate(async () => window.axe.run());
        report.violations.push(
          ...result.violations.map((v) => ({
            id: v.id,
            impact: v.impact,
            file: "app/src/main/assets/" + file,
            nodes: v.nodes.map((n) => ({
              target: n.target,
              failureSummary: n.failureSummary,
            })),
          })),
        );
        report.checks.push({
          file,
          passes: result.passes.length,
          incomplete: result.incomplete.length,
        });
      }
    }
    if (report.pageErrors.length || report.violations.length)
      process.exitCode = 1;
  } finally {
    await browser.close();
  }
}
main()
  .catch((error) => {
    report.executionError = error.message;
    process.exitCode = 1;
  })
  .finally(() => {
    fs.writeFileSync(
      path.join(out, mode === "smoke" ? "browser.json" : "axe.json"),
      JSON.stringify(report, null, 2) + "\n",
    );
    console.log(
      JSON.stringify({
        mode,
        checks: report.checks.length,
        violations: report.violations.length,
        pageErrors: report.pageErrors.length,
        executionError: Boolean(report.executionError),
      }),
    );
  });
