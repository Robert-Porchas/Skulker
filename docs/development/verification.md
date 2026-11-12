# Milestone verification record

A milestone passes only after its implemented acceptance criteria are checked. Configuration
alone does not establish that a deployment, integration, or benchmark works.

## Milestone 0 — passed

- Java 21 Gradle multi-project build and pinned wrapper established.
- `./gradlew --no-daemon spotlessApply clean build` passed on Temurin 21.0.13.
- Initial engine slice: 72 tests passed, zero failed or skipped.
- Production bytecode targets Java 21; `jdeps -s` reports only `java.base`.
- CI YAML, immutable action pins, permissions, documentation links, and combined diff reviewed.
- Local commit: `7cdb158` (`chore: establish Java 21 foundation and initial engine slice`).
- Remote GitHub CI has not run; branch protection is manual and unverified.

## Milestone 1 — passed

- CSV ingestion, remaining indicators, immutable backtest contracts, crossover simulation,
  exact decimal accounting, trade/equity history, metrics, and execution metadata implemented.
- `./gradlew --no-daemon spotlessApply clean build` passed on Temurin 21.0.13.
- 217 deterministic tests passed, zero failed or skipped. Includes CSV-to-result workflow,
  nontrivial moving-average windows, missing dates, fees, unaffordable orders, terminal
  positions, result-history integrity, and severe-loss annualization regressions.
- Independent reviews checked CSV policies, indicators, execution timing, and financial precision.
  Identified annualization cancellation was fixed and covered before acceptance.
- `jdeps -s` confirms only `java.base`; calculation and execution policies match source.
- Combined diff and formatting reviewed. No external market data or cloud resources used.

## Environment prerequisites for later gates

The Linux Docker wrapper reports disabled WSL integration and no local socket is available.
The user has been asked to enable integration. An escalated read-only `docker.exe version`
check confirms Docker Desktop's Linux engine is running through the Windows CLI.
Real PostgreSQL/RabbitMQ and local Kubernetes checks still require validated runtime access;
containerized checks are an alternative if Linux integration remains unavailable.
Cloud provisioning remains separately authorized and must include cost and teardown review.
