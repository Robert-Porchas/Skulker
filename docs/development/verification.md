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

## Milestone 2 — passed

- `DOCKER_COMMAND=docker.exe bash scripts/verify-in-docker.sh` completed `./gradlew --no-daemon clean build`
  successfully on SHA-pinned Temurin 21.0.13 in local Docker Desktop.
- 257 tests passed: 217 engine, 24 store validation, 3 JSON contract, and 13 real PostgreSQL integration tests.
  Zero failures, errors, or skipped tests. Integration XML was copied to the ignored build directory.
- Real PostgreSQL 17.11 exercised Flyway migrations, Hibernate schema validation, HTTP dataset/job/result
  workflows, invalid uploads/requests, exact JSONB decimals, present/empty optional metrics,
  concurrent claims, expired ownership fencing, bounded recovery, and atomic completion.
- Executable API packaging, compiler lint, and formatting passed. Source review found no blocking defects.
- The verification container was removed automatically. Only local image/cache artifacts remain;
  no application data volume or cloud resources were created by the checks.

## Milestone 3 — passed

- Full `DOCKER_COMMAND=docker.exe bash scripts/verify-in-docker.sh` acceptance build passed on Java 21.
- 296 tests passed: 217 engine, 32 store, 6 shared contracts, 14 worker unit, 3 JSON contract,
  15 PostgreSQL API integration, and 9 PostgreSQL/RabbitMQ worker integration tests.
  Zero failures, errors, or skipped tests.
- Real-broker checks exercised confirmed persistent publication, duplicate delivery, publication
  interruption before outbox commit, result commit before acknowledgment/redelivery, expired-owner
  fencing, bounded processing retries/dead letters, malformed message routing, and unroutable retry.
- An actual Spring Rabbit listener consumed a broker delivery and committed the expected result.
- Parameter-batch creation, valid-combination generation, and atomic invalid-batch rejection passed.
- Source review caught unclaimable pending-task acknowledgment and severe-loss precision cases;
  fixes and regression coverage passed before acceptance. Durable timing policies remain separate
  from clock-free financial computation.
- Both executable applications, all library modules, compiler lint, formatting, and combined diff passed.
  Only local test containers/images/cache were used; no pushes, merges, or cloud resources.
