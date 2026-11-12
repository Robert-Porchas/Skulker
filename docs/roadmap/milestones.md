# Incremental roadmap

Delivery proceeds sequentially through the milestones below. Completion claims must be backed by executed validation in the [verification record](../development/verification.md).

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

## Milestone 1 implementation

The engine includes immutable price/configuration/result models, strict CSV ingestion, daily and cumulative returns, SMA, rolling sample volatility, maximum drawdown, long-only crossover simulation, exact decimal accounting, trade history, equity curves, performance metrics, and deterministic tests. Its acceptance gate is recorded separately after integrated checks pass.

## Remaining sequence

1. Verify Milestone 1 before introducing API/database dependencies.
2. Add dataset registration, asynchronous job contracts, durable persistence, REST endpoints, and real PostgreSQL integration tests.
3. Verify each later milestone before introducing the next layer. Cloud provisioning remains separately approved.

Any reprioritization should update this roadmap and the README together. No paid market data, cloud resources, or frontend scaffolding is needed to finish the engine.
