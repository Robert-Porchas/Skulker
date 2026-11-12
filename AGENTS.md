# Skulker engineering instructions

Skulker is a historical financial research and backtesting platform. The current
implementation starts with a pure Java 21 library, not a live-trading system.

- `backend/financial-engine` owns immutable price models and deterministic calculations.
  Keep calculations free of Spring, persistence, messaging, I/O, network access, and system-clock dependencies.
  The CSV adapter accepts a caller-owned Reader; it does not open files or access storage itself.
- `backend/job-store` owns PostgreSQL entities, Flyway migrations, and transactional state/lease/result writes.
  Do not expose JPA entities through HTTP. Preserve fencing tokens and atomic result/state commits.
- `backend/api-service` owns HTTP validation, JSON adapters, and local scheduled execution.
  Keep public responses free of CSV contents, lease tokens, and internal failure details except explicit CSV downloads.
- `shared-domain` owns framework-free task references. `messaging` owns topology and confirmed outbox dispatch.
  `worker-service` owns manual acknowledgments and execution. Acknowledge only after durable completion/retry
  or a verified active/terminal duplicate. Never acknowledge a merely not-yet-claimable pending task.
  Preserve retry availability timestamps, attempt budgets, and current-owner fencing.
- Work through authorized milestones sequentially. Record each acceptance gate in
  `docs/development/verification.md` before starting the next milestone.
  Add concrete modules as needed; do not create empty placeholder modules.
- Read `docs/architecture/` and relevant ADRs before changing contracts or conventions.

## Build and validation

Install JDK 21 and run from `backend/` using the checked-in wrapper:

```sh
./gradlew spotlessApply
./gradlew clean build
```

`build` compiles production and test code with Java 21, runs engine JUnit 5 and service JUnit 6 tests, checks
Spotless formatting, and treats all javac lint warnings as errors. No standalone
static analyzer is configured. Test reports: `financial-engine/build/reports/tests/test/`.
The full build requires Docker for real PostgreSQL/RabbitMQ Testcontainers tests; missing Docker must fail, not skip.
First builds require network access for dependencies/images. Tests never call market-data services.
Focused unit checks: `./gradlew :financial-engine:test :job-store:test :api-service:test`.
See `docs/development/local-api.md` for Docker Desktop verification and local API setup.

## Code and financial correctness

- Use `dev.skulker.engine` packages, Java records where appropriate, and Google Java Format.
- Store prices/money as `BigDecimal`. Document arithmetic and conversion boundaries.
- Preserve input alignment for indicators; unavailable values use `OptionalDouble.empty()`.
  Reject invalid prices, symbols, duplicate dates, and unsorted series explicitly.
- Never fill missing dates or substitute zero for an unavailable indicator silently.
- Keep tests deterministic with hand-calculated expectations, including empty/insufficient data.
- Future strategies must observe signals at completed close and execute at the next available open;
  prevent look-ahead bias and negative long-only balances.

## Git and collaboration

- Inspect status/base branch first; preserve user changes. Use short-lived feature branches from `main`.
- Do not commit, push, open PRs, merge, or rewrite shared history without explicit authorization.
- Recommend small Conventional Commit boundaries; PRs state behavior, tests, and material risks.
- Delegate independent substantial tasks when tools are available. Assign exclusive file ownership
  and stable contracts before editing; review the combined changes and run the integrated build.
- Never commit credentials, local configuration, state, build outputs, or large datasets.

## Infrastructure safety

Local development must not require AWS credentials. Cloud resources, deployments,
and `terraform apply` require explicit approval plus a cost estimate and teardown plan.
Do not add live trading, broker integration, or real-money execution.

## Definition of done

Relevant tests and formatting pass; the project builds on Java 21; financial conventions,
README commands, and architecture docs match behavior; the combined diff is reviewed;
no secrets or generated build artifacts are included. Report failed or unavailable checks honestly.
