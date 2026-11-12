# Incremental roadmap

The initial delivery covers the repository foundation and a small financial-engine slice. Completion claims must be backed by executed validation; the broader Milestone 1 remains open.

| Milestone | Scope | Acceptance gate |
| --- | --- | --- |
| 0 — Repository foundation | Gradle wrapper and multi-project build, Java 21, CI, repository conventions, architecture documentation | Compile, tests, formatting, and configured checks pass; Git diff reviewed |
| 1 — Financial engine | Immutable domain, CSV ingestion, indicators, SMA crossover, simulation, metrics | Deterministic behavior tests and documented calculation/execution policies |
| 2 — API | Spring Boot, PostgreSQL, Flyway, dataset/job/result REST endpoints | Transaction and API integration tests, including input failures |
| 3 — Distributed workers | RabbitMQ, task generation, retries, dead letters, idempotent results | Duplicate-delivery and worker-crash recovery tests |
| 4 — Local Kubernetes | Containers, kind, Helm, KEDA | Reproducible local deployment and verified probes/resources/scaling |
| 5 — Observability and benchmarking | Prometheus, Grafana, load tests | Recorded 1/2/4/8-worker throughput, latency, failures, CPU and queue metrics |
| 6 — Optional AWS | Terraform, disposable EKS, IAM, cloud storage | Explicit deployment approval, cost estimate, teardown and billing-resource verification |
| 7 — Dashboard | Next.js dataset/job/results/strategy comparison UI | Tested user flows against the established API |

## Initial Milestone 1 slice

Implemented scope is limited to `PriceBar`, `HistoricalPriceSeries`, simple moving average, daily simple returns, and deterministic unit tests. It does not constitute a usable trading-strategy backtester.

## Recommended next session

1. Add a CSV reader with a documented schema, explicit symbol input, row-numbered errors, strict numeric/date parsing, blank-row policy, and rejection of duplicate or unsorted dates. Keep ingestion outside the calculation methods.
2. Add cumulative returns, rolling volatility, and maximum drawdown with explicit warm-up and population/sample conventions and hand-calculated tests.
3. Define immutable strategy configuration and result models, fee and sizing policies, and completed-bar/next-open execution tests before implementing the crossover simulator.
4. Complete Milestone 1 acceptance criteria before starting API or infrastructure work.

Any reprioritization should update this roadmap and the README together. No paid market data, cloud resources, or frontend scaffolding is needed to finish the engine.
