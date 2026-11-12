# Local research dashboard

The dashboard is a private, single-tenant Next.js application backed by Skulker's existing REST API and persisted datasets, jobs, and results. It does not submit live orders, connect to brokers, or use a market-data provider. AWS remains unprovisioned, and the application still stores CSV datasets in PostgreSQL; the future S3 adapter is not implemented.

The frontend pins Next.js 16.4.6, React 19.3.1, TypeScript 6.0.3, and Tailwind CSS 4.3.5. Use Node.js 24.19.x and the committed npm lockfile. The backend separately requires its documented Java 21 and local service setup.

## Start the dashboard

First start PostgreSQL, RabbitMQ, API, and worker using the [local API instructions](local-api.md). Keep those processes running. For an existing verified kind deployment, forward the API from another terminal at the repository root instead:

```sh
export KUBECONFIG="$PWD/.tooling/kubeconfig"
export PATH="$PWD/.tooling/bin:$PATH"
scripts/local-cluster.sh status
kubectl --context kind-skulker --namespace skulker port-forward svc/skulker-api 8080:8080 --address=127.0.0.1
```

Then, from the repository root:

```sh
cd frontend
npm ci --ignore-scripts
npm run dev
```

Open `http://127.0.0.1:3000`. Development and production start commands bind to localhost. `SKULKER_API_URL` is a **server-only** setting, defaulting to `http://127.0.0.1:8080`. Set it before starting Next.js when the API uses another local address, or create a private `.env.local` based on `.env.example`. Do not prefix it with `NEXT_PUBLIC_`; browsers use the dashboard's same-origin `/api` routes.

Production-mode local verification uses the same backend and environment:

```sh
npm run build
npm run start
```

Stop the development server before starting another process on port 3000. The frontend is not added to the Helm chart or provisioned on AWS by this milestone.

## Data and financial conventions

Use a user-supplied CSV or the small deterministic [sample](../../datasets/samples/ohlcv.csv). The [CSV schema](../../datasets/samples/README.md) and [API limits](local-api.md) remain authoritative. Registration, job submission, and parameter sweeps write to the existing backend; refreshing a page must not create another job. Job state comes from durable API records rather than invented browser-side execution percentages.

The sample has five observations. A moving-average window larger than the available history produces no signals, and a valid backtest can finish without trades. A final-day signal cannot execute without a subsequent bar.

Capital and fixed transaction fees are decimal strings in forms and outgoing JSON. The server proxy preserves monetary and whole-share JSON number tokens as strings using Node's JSON reviver `context.source`, rather than the parser's approximate `Number` value. Exact-value text formatting groups those decimal digits without calling `Number`. Ratios and chart coordinates use approximate numbers solely for presentation; the Java engine remains responsible for all financial calculations.

Returns are decimal ratios displayed as percentages: `0.05` means 5%. Maximum drawdown is a nonnegative loss magnitude: `0.20` means a 20% peak-to-trough loss. A missing annualized return displays as unavailable; it is not zero. Annualization can be unavailable for a single trading date.

The simulated strategy observes crossover signals after a completed daily close and fills at the next available day's open. It buys whole shares, charges the configured fixed fee per executed trade, skips unaffordable orders, and keeps an open final position marked to the last close. Missing trading dates are not filled. See the full [engine conventions](../architecture/calculations.md) before interpreting a result. Synthetic fixtures validate behavior and provide load tests; they do not demonstrate profitability.

Comparisons must identify each result's symbol, date range, configuration, and initial capital. A normalized equity view compares growth relative to each run's own starting capital; it does not make different date ranges equivalent or convert a comparison into an investable portfolio. Exact money and trade values remain available separately from approximate charts.

Dataset and job pages contain 25 records; summary counts describe loaded or visible records,
not a global database total. Active jobs and tracked sweeps refresh every three seconds while
this page is open. Sweep tracking and comparison selections belong to the current page session;
the underlying datasets, jobs, and results remain durable after refresh. Compare up to four
completed runs. Lines sample at most 400 observations per run, while the point inspector uses
all observations and exact source values. Trade history pages contain 20 trades. The browser
cache retains at most eight recent results to bound memory use for long equity histories.
Viewing a result or comparison moves focus and scrolls to its region, including when the job
table is long. Scrolling follows the browser's reduced-motion preference.

## Local API boundary

The Next.js server proxies only the implemented dataset, job, batch, and result routes through an explicit method/path allowlist. It uses a fixed server-configured backend address, a 20-second upstream timeout, and a 3 MiB request limit. These limits do not override the API's stricter 2 MiB CSV upload limit. Requests must use a loopback Host; POST origins must match that bound host even when Next canonicalizes its internal URL. Invalid routes and upstream failures must produce bounded errors rather than exposing backend credentials or internal exception details.

This boundary is intended for local use. It does not add user accounts, tenant isolation, or permission checks to the underlying API. Public hosting requires a separate authentication, authorization, and deployment design. Do not expose the Next.js server or research API publicly using port forwarding, tunnels, or host overrides as part of this setup.

## Validation commands

From `frontend/`:

```sh
npm run lint
npm run typecheck
npm test
npm run format:check
npm run build
```

`npm run format` applies formatting. `npm ci --ignore-scripts` installs the locked dependency graph;
initial dependency and browser downloads need network access, while tests use deterministic
fixtures without market-data services. Type checking first generates Next.js route declarations;
generated declarations and build files stay ignored. Unit tests use Node's native TypeScript
support and explicit in-process isolation so individual assertions are reported.

The maintained ESLint 10 configuration combines Next.js core web vitals, React Hooks, and
TypeScript rules directly. TypeScript 6 matches the parser's supported range. The lint-only
`minimatch` override pins the patched 11.0.1 release; lint and type checks validate compatibility.

Playwright uses headless Chromium from the ignored repository cache. Install that browser once, then run the mock-backed suite with port 3000 free:

```sh
PLAYWRIGHT_BROWSERS_PATH=../.tooling/playwright npx playwright install chromium
PLAYWRIGHT_BROWSERS_PATH=../.tooling/playwright npm run test:e2e
```

The test configuration starts its own Next.js server on port 3000 and a deterministic mock backend on `127.0.0.1:19081`. Mock tests verify browser behavior and proxy handling, not PostgreSQL persistence or RabbitMQ execution.
Startup waits for bounded process readiness messages rather than probing an unused port first;
some local firewalls drop closed-port connections. The tests then verify real HTTP behavior.
An occupied port fails server startup; existing processes are never silently reused.

For live verification, keep the real API and workers running and provide the API URL. For kind, forward the API to port 18080 in another terminal, using the same owned kubeconfig and context guard shown above:

```sh
kubectl --context kind-skulker --namespace skulker port-forward svc/skulker-api 18080:8080 --address=127.0.0.1
```

Then run from `frontend/`:

```sh
SKULKER_LIVE_API_URL=http://127.0.0.1:18080 \
  PLAYWRIGHT_BROWSERS_PATH=../.tooling/playwright npm run test:e2e
```

Live mode uses the actual backend through the same Next.js proxy and selects the compatible live workflow rather than starting the mock server. It creates a small fixture dataset and backtest in the local application and retains those records. Successful browser checks must be recorded only after execution in [verification.md](verification.md); these commands alone are not evidence that a test passed.
