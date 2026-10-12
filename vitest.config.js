import { defineConfig, configDefaults } from "vitest/config";

export default defineConfig({
  test: {
    globals: true,
    environment: "node",
    // Playwright specs run via `npm run test:e2e`, never under vitest;
    // the CJS dashboard harness is a plain-node script chained in `npm test`.
    exclude: [...configDefaults.exclude, "tests/e2e/**", "tools/**/*.test.cjs"],
    coverage: {
      provider: "v8",
      reporter: ["text", "json", "html"],
      thresholds: {
        lines: 90,
        functions: 90,
        branches: 85,
        statements: 90,
      },
    },
  },
});
