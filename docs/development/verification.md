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

At Milestone 2 initialization, the Linux Docker wrapper reported disabled WSL integration
and no local socket. A read-only `docker.exe version` check confirmed Docker Desktop's
Linux engine through the Windows CLI, enabling containerized PostgreSQL/RabbitMQ checks.
Native WSL integration was restored and verified during Milestone 4; Milestone 5's full
Java build and subsequent Kubernetes/browser checks used the native runtime successfully.
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

## Milestone 5 — passed

- Native Java 21 `./gradlew --no-daemon spotlessApply build` passed: 314 tests, zero failures/errors/skips, including real PostgreSQL/RabbitMQ integration tests. Formatting, compiler lint, Helm off/on lint/render, shell/Python checks, and combined review passed.
- Private Prometheus/Grafana deployed successfully. `python3 scripts/monitoring-smoke.py --timeout-seconds 180` passed live API/broker scrapes, all six durable status gauges, authenticated datasource health, and the 12-panel operations dashboard.
- Actual service-account authorization permits namespaced EndpointSlice/pod discovery and denies Secret/node reads. Grafana automatic plugin preinstallation/updates are disabled; credentials are preserved separately from Helm values.
- `python3 scripts/benchmark-workers.py --trials 3 --timeout-seconds 5400` passed all twelve 100-task trials and four 16-task warmups at exact 1/2/4/8 Ready worker counts. All 1,264 tasks succeeded; CPU samples and queue depths were measured. KEDA settings were restored and workers returned to zero.
- Median throughput: 6.69/10.69/10.51/9.59 tasks per second for 1/2/4/8 workers. The [benchmark report](worker-benchmark.md) records environment, latency, CPU, queue peaks, evidence hashes, and interpretation limits.
- Load preparation exposed a 100-job page memory failure; metadata projections fixed it. The repeated live request passed in 49 ms within the same 512 MiB API limit. Updated application and monitoring pods had zero restarts after acceptance.
- Independent agents implemented/reviewed metrics, projections, monitoring, smoke checks, and benchmark safety; root reviewed integration and ran the actual checks. Only local resources and authorized local commits were used.

## Milestone 6 — unprovisioned configuration gate passed

- The user explicitly selected unprovisioned AWS and continuation to the dashboard. Cloud provisioning and runtime application acceptance are therefore deferred, not claimed as completed deployments.
- `bash scripts/bootstrap-terraform.sh` installed checksum-pinned Terraform 1.17.2. `bash scripts/verify-terraform.sh` passed recursive formatting, provider initialization with no backend initialization/read-only lockfile, schema validation, and thirteen plan-only mocked AWS tests (zero failed/skipped). AWS provider 6.72.0 is locked with official platform checksums.
- Tests cover public/no-NAT routing, explicit restricted EKS access, no implicit creator administration, bounded nodes, IMDSv2, encrypted gp3, standard credits, exact CNI/Pod Identity trust scopes, private versioned TLS-only S3, and unsafe-input rejection. The initial nested mock-data shape failure was corrected before the canonical integrated run passed.
- Independent review found no blocking source or documentation issue. Shell syntax/ShellCheck, repository formatting, and combined diff passed. CI uses the same mock-only verifier and receives no AWS credentials.
- [ADR-008](../adr/008-optional-aws-foundation.md) is accepted for the architectural boundary. The [AWS guide](aws.md) records official source-backed costs, state prerequisites, explicit version resolution, teardown order, and remaining-resource/billing verification.
- No AWS credentials, account APIs, real cloud plans, applies, resources, registry pushes, or deployments were used. Local kind remains the verified application runtime. Cloud storage integration, CSI/application deployment, and regional compatibility remain future approved work.

## Milestone 7 — passed

- Implemented the local Next.js dashboard for CSV upload/download, paginated datasets/jobs, single backtests, bounded parameter sweeps, durable status polling, results, exact trade tables, normalized equity inspection, and four-run comparisons. The Java engine remains the sole financial simulation implementation.
- `npm run format:check`, `npm run typecheck`, `npm run lint`, `npm test`, and `npm run build` passed on Node 24.19.0. Twenty unit tests passed with zero failures/skips, including lossless monetary/share tokens beyond JavaScript's safe integer range, body limits, route/query restrictions, local origins, and validation/error handling.
- A clean `npm ci --ignore-scripts --no-fund` reproduced the locked dependency graph, reported zero npm vulnerabilities, and passed formatting/lint/type/unit checks again. Generated declarations, dependency caches, browser reports, and build outputs remain ignored.
- `PLAYWRIGHT_BROWSERS_PATH=../.tooling/playwright npm run test:e2e` passed all seven Chromium workflows against the production build and deterministic HTTP fixtures. Covers empty/error states, invalid inputs, CSV errors, exact large decimals, mobile overflow/keyboard labels, full execution/comparison/sweep flow, and results focus/viewport behavior.
- `SKULKER_LIVE_API_URL=http://127.0.0.1:18080 PLAYWRIGHT_BROWSERS_PATH=../.tooling/playwright npm run test:e2e` passed its selected live workflow against the CA-guarded kind API, real PostgreSQL/RabbitMQ, and workers. Final run: 2026-11-12 03:47:29–03:48:06 UTC, one expected test, zero failed/skipped/flaky tests.
- Live assertions verified exact six-bar CSV retrieval, SMA 1/2 with capital 100 and fee 1 ending at 27, one next-open buy at 10, total return -73%, drawdown 73%, SMA 2/3 ending at 99, two-result comparison, and two successful sweep tasks. These are correctness fixtures, not profitability claims. Each live run retains its dataset and four completed leaf jobs locally.
- Browser checks exposed a Next internal-host/origin mismatch; the proxy now validates the bound loopback Host and rejects foreign authorities, with regression tests. Bounded startup-output readiness fixes this host's closed-port probe hang. Test locators were corrected to use the controls' actual accessible roles before final acceptance.
- Separate agents owned UI, tests, and architecture documentation/review. Root integrated the proxy/toolchain/CI, reviewed changes, ran acceptance checks, and visually inspected desktop/mobile screenshots. Evidence remains ignored in `.tooling/dashboard-live.json`, `.tooling/dashboard-live-playwright.json`, and `.tooling/dashboard-desktop.png`.
- Four CI jobs are configured with read-only permissions and immutable action pins. Remote CI and branch protection remain unverified. Test servers/forwards were cleaned up; the owned kind application remains local. No hosting, pushes, merges, or cloud provisioning occurred.
