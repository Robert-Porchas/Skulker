# ADR-009: Local dashboard and server-side API boundary

Status: **Accepted** after integrated review, production build, twenty unit tests, seven Chromium fixture workflows, and a live PostgreSQL/RabbitMQ worker workflow passed.

## Context

Skulker already owns dataset validation, durable job state, distributed execution, and deterministic financial accounting in its Java backend. A dashboard needs to expose those capabilities without duplicating calculations, inventing progress, losing decimal precision, or making the unauthenticated research API a public service.

## Decision

Use a separate Next.js application with React, TypeScript, and Tailwind CSS. Pin its npm dependencies and Node 24 runtime independently of the Java build. Keep the dashboard local and single-tenant in this milestone; no cloud hosting, brokerage integration, or frontend Helm workload is introduced.

Browser requests use same-origin `/api` endpoints. A narrow Next.js server proxy forwards only the existing backend route/method allowlist to the server-only `SKULKER_API_URL`, defaulting to the loopback API. Apply a 20-second upstream timeout and a 3 MiB request bound. The backend remains authoritative for validation, CSV storage, transactional writes, leases, strategy calculations, and results.

Preserve JSON monetary values and whole-share counts as decimal strings at the browser boundary. Node 24's JSON reviver source context retrieves the original number token independently of any rounded `Number` value. Submit decimal form strings directly; display exact values without converting them through floating-point arithmetic. Approximate chart coordinates and percentage ratios do not enter portfolio accounting.

Use deterministic mock-backed browser tests for UI and proxy behavior, and a separate explicitly selected live test against the actual local API/workers for persistence and execution evidence. Record executed checks in the milestone verification log rather than treating test configuration as proof of success.

## Consequences

- The financial engine remains infrastructure-independent, with one implementation of backtesting behavior.
- Browser-visible contracts can remain precise despite Java `BigDecimal` prices and `long` share counts exceeding JavaScript's safe numeric range.
- Upstream settings and credentials stay on the server; browser code needs neither cross-origin backend configuration nor direct database access.
- Comparisons must explain normalized equity, original capital, and date ranges. Unavailable values remain explicit, and sampled charts cannot replace exact trade/result data.
- The proxy introduces an additional local server process and requires meaningful error, timeout, request-bound, and precision tests.
- The proxy is not an authentication or authorization system. Public or multi-tenant deployment requires a separate reviewed design. AWS and the future S3 adapter remain unprovisioned/unimplemented.

See the [dashboard development guide](../development/dashboard.md) for commands and interpretation conventions.
