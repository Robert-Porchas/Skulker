# Skulker architecture

Skulker is a historical financial research platform. The current implementation is a Java library; it has no live trading, service runtime, database, queue, cloud resources, or frontend.

## Current boundary

```text
backend/                       Gradle multi-project root
└── financial-engine/          Pure Java 21 library and JUnit 5 tests
```

The engine owns validated historical prices and deterministic calculations. It accepts immutable in-memory data and returns values without network, filesystem, clock, or framework dependencies. This makes the same calculations usable from unit tests, a future API, and future distributed workers.

`PriceBar` represents one symbol and trading date, with decimal OHLC prices, nonnegative integral volume, and optional adjusted close. `HistoricalPriceSeries` establishes a single-symbol, strictly chronological, immutable collection. `FinancialIndicators` calculates daily simple returns and simple moving averages using raw closing prices. See [calculation conventions](calculations.md).

There is only one implemented module. Shared build settings live in the Gradle root; convention plugins and shared-domain interfaces should be introduced only when multiple concrete consumers need them.

## Planned boundaries

| Component | Responsibility | Dependency direction |
| --- | --- | --- |
| `financial-engine` | Indicators, strategy signals, simulation, performance metrics | Java standard library only at runtime |
| `api-service` | Validate requests, register datasets, create jobs/tasks, expose status/results | Engine and future shared contracts |
| `worker-service` | Load datasets, execute tasks, persist results, acknowledge deliveries | Engine and future shared contracts |
| `shared-domain` | Stable job/task identifiers and transport contracts needed by both services | No dependency on service implementations |
| Infrastructure | Runtime configuration, deployments, storage, messaging, monitoring | Separate from financial algorithms |

These are plans, not scaffolded modules. Service persistence entities and message envelopes must not enter the engine merely to share code.

## Future distributed execution

PostgreSQL will be authoritative for task state and results. RabbitMQ will transport durable tasks with at-least-once delivery. A unique task identifier and transactional, idempotent result persistence must make repeated deliveries safe. Workers will acknowledge only after durable success, with bounded retries and a dead-letter path for exhausted failures.

The task lifecycle will distinguish `PENDING`, `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, and `CANCELLED`. Before implementation, Milestone 3 must specify the transition table, retry ownership, stale-worker recovery, and cancellation races. A transactional outbox is a candidate for the database-to-broker failure window; it is not implemented now.

Local development precedes cloud deployment. Compose, kind, Helm, and KEDA are future tools, with measured scaling rather than assumed throughput gains. AWS resources require separate explicit authorization, a cost estimate, and a teardown verification plan.
