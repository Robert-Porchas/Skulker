# Skulker architecture

Skulker is a historical financial research platform. Its financial engine is a pure Java library;
the API, PostgreSQL store, and RabbitMQ workers provide durable distributed processing.
There is no live trading, cloud deployment, or frontend.

## Current boundary

```text
backend/                       Gradle multi-project root
├── financial-engine/          Pure Java 21 library and JUnit 5 tests
├── job-store/                 PostgreSQL/Flyway persistence and leased job state
├── api-service/               HTTP dataset/job/batch interface
├── shared-domain/             Framework-free task reference contracts
├── messaging/                 Confirmed outbox publication and broker topology
└── worker-service/            Manual-acknowledgment execution and failure handling
```

The engine owns validated historical prices and deterministic calculations. The calculation core accepts immutable in-memory data and returns values without network, filesystem, clock, or framework dependencies. A separate CSV adapter consumes a caller-owned Reader. This makes the same calculations usable from unit tests, a future API, and future distributed workers.

`PriceBar` represents one symbol and trading date, with decimal OHLC prices, nonnegative integral volume, and optional adjusted close. `HistoricalPriceSeries` establishes a single-symbol, strictly chronological, immutable collection. `FinancialIndicators` calculates returns, moving averages, sample volatility, and drawdown. `MovingAverageBacktester` simulates a long-only crossover strategy with next-open execution, whole shares, exact decimal cash accounting, and immutable results. See [calculation conventions](calculations.md).

There are six implemented modules. Shared build settings live in the Gradle root.
API and worker depend on engine, store, and messaging; messaging uses store and shared transport contracts.
The engine has no dependency back to services, storage, messaging, or shared task contracts.
See [durable local execution](../adr/006-durable-api-job-store.md) for storage and lease decisions.

## Planned boundaries

| Component | Responsibility | Dependency direction |
| --- | --- | --- |
| `financial-engine` | Indicators, strategy signals, simulation, performance metrics | Java standard library only at runtime |
| `api-service` | Validate requests, register datasets, create jobs/tasks, expose status/results | Engine and future shared contracts |
| `worker-service` | Load datasets, execute tasks, persist results, acknowledge deliveries | Engine and future shared contracts |
| `shared-domain` | Stable job/task identifiers and transport contracts needed by both services | No dependency on service implementations |
| Infrastructure | Runtime configuration, deployments, storage, messaging, monitoring | Separate from financial algorithms |

These are plans, not scaffolded modules. Service persistence entities and message envelopes must not enter the engine merely to share code.

## Distributed execution

PostgreSQL is authoritative for task state and results. RabbitMQ transports durable tasks with
at-least-once delivery. Each leaf job identifier is a task identifier; parameter batches group
independent dataset/window combinations. Leased, fenced result persistence makes repeated
deliveries safe. Workers acknowledge after durable decisions, with bounded retries and dead letters.

| From | To | Trigger |
| --- | --- | --- |
| `PENDING` | `QUEUED` | Confirmed publication, if execution has not already claimed the task |
| `PENDING` or `QUEUED` | `RUNNING` | Atomic claim with a fresh fencing token |
| `RUNNING` | `SUCCEEDED` | Atomic result commit by the current unexpired owner |
| `RUNNING` | `PENDING` | Durable retry or expired-lease recovery before the attempt limit |
| `RUNNING` | `FAILED` | Execution/recovery attempt budget exhausted |
| `PENDING` or `QUEUED` | `FAILED` | Current publication attempt budget exhausted |

Terminal states do not reopen. `CANCELLED` is reserved in the state contract; cancellation/replay
endpoints are not implemented. A transactional outbox closes the database-to-broker write window.
See [publication, recovery, and acknowledgment policies](../adr/007-outbox-and-distributed-execution.md).

Local development precedes cloud deployment. Compose, kind, Helm, and KEDA are future tools, with measured scaling rather than assumed throughput gains. AWS resources require separate explicit authorization, a cost estimate, and a teardown verification plan.
