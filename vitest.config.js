import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    globals: true,
    environment: "node",
    // Explicit collection boundary: each suite is run by the runner it was written for, so a
    // "failing" run means a failing test rather than a mismatched runner.
    //   * frontend/**/*.test.ts        - Vitest unit tests (this command)
    //   * tests/e2e/**/*.spec.ts       - Playwright specs (`npm run test:e2e`, playwright.config.js)
    //   * tools/factory/dashboard.test.cjs - a plain node script that asserts against the generated
    //     dashboard (docs/factory/FACTORY.html, produced by
    //     `python3 -m tools.factory.automation overview --publish-doc`); it is executed with node
    //     and its input artifact is not part of a clean checkout.
    include: ["frontend/**/*.test.ts", "frontend/**/*.test.tsx"],
    exclude: [
      "**/node_modules/**",
      "**/dist/**",
      "tests/e2e/**",
      "tools/factory/dashboard.test.cjs",
    ],
    coverage: {
      provider: "v8",
      reporter: ["text", "json", "html"],
      thresholds: {
        lines: 90,
        functions: 90,
        branches: 85,
        statements: 90
      }
    }
  }
});
