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

## Milestone 4 — passed

- API/worker images built from SHA-pinned Java 21 images with UID/GID 10001; both application JARs packaged successfully.
- Tool bootstrap, Helm lint, both scaling-mode renders, Bash syntax, ShellCheck, Python syntax, and infrastructure CI commands passed locally.
- Three Kubernetes 1.36.4 kind nodes became Ready. KEDA 2.21.0 and the application Helm release deployed successfully.
- `python3 scripts/kubernetes-smoke.py --autoscale --timeout-seconds 900` passed. The API-to-worker fixture returned final value 27 with the expected next-open trade.
- Real queue load requested eight workers and observed five Ready workers. Two batches completed all 200 independent backtests; final ready/unacknowledged queue counts were both zero. Workers subsequently scaled back to zero.
- Live workload probes, CPU/memory requests/limits, release ownership, and namespace were checked. Current API/database/broker pods are Ready with zero restarts.
- Deployment checks identified and fixed BusyBox checksum-option portability, cross-namespace broker DNS, and initial database DNS/readiness retries. A wrapper execution disrupted by editing its live source was rerun unchanged and completed successfully before acceptance.
- Evidence: ignored `.tooling/kubernetes-smoke.json`, collected 2026-11-12 02:05–02:07 UTC. This is an autoscaling acceptance check, not a 1/2/4/8 throughput comparison.
- Native WSL Docker access was restored and verified during this milestone. All resources remain local; no registry pushes or cloud resources.
