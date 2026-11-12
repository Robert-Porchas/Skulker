import { defineConfig, devices } from "@playwright/test";

const live = process.env.SKULKER_LIVE_API_URL;
const backend = live || "http://127.0.0.1:19081";

export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.spec.ts",
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: live ? 180000 : 60000,
  expect: { timeout: live ? 90000 : 15000 },
  grep: live ? /@live/ : undefined,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://127.0.0.1:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    ...(!live
      ? [
          {
            command: "node e2e/mock-backend.ts",
            // A closed-port HTTP preflight can hang on hosts that drop connections.
            // This token is emitted only after our fixture successfully binds.
            wait: { stdout: /Skulker deterministic mock API on/ },
            timeout: 30000,
          },
        ]
      : []),
    {
      command: "npm run start -- --port 3000",
      wait: { stdout: /Ready in/ },
      env: { SKULKER_API_URL: backend },
      timeout: 60000,
    },
  ],
});
