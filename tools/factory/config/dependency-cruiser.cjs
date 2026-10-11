module.exports = {
  forbidden: [
    {
      name: "no-circular-test-dependencies",
      severity: "error",
      from: {},
      to: { circular: true },
    },
    {
      name: "tests-must-not-import-server-internals",
      severity: "error",
      from: { path: "^js-tests/" },
      to: { path: "^backend/" },
    },
  ],
  options: { doNotFollow: { path: "node_modules" } },
};
