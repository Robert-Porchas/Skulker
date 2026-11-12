# Skulker

Skulker is a historical financial backtesting and analytics platform being built
incrementally. It is a research and engineering project; it does not execute live
trades or connect to brokerages. Historical performance does not predict future returns.

## Current implementation

Milestone 0 establishes the Java build, repository conventions, CI, and architecture
documentation. Milestone 1 provides:

- Immutable, validated `PriceBar` and `HistoricalPriceSeries` models
- Strict historical CSV ingestion with row-numbered validation errors
- Daily/cumulative returns, simple moving averages, rolling sample volatility, and drawdown
- Long-only SMA crossover simulation with next-open execution and fixed fees
- Immutable trades, equity curves, performance metrics, and deterministic execution metadata
- Explicit unavailable values for warmup periods
- Deterministic JUnit 5 tests using small in-memory fixtures
- REST dataset registration, job submission/status, and stored result retrieval
- PostgreSQL/Flyway persistence and a local asynchronous job runner with leased claims

RabbitMQ workers, deployment infrastructure, and a dashboard are planned.
Milestone check results, including pending integration gates, are recorded in the
[verification record](docs/development/verification.md).

## Architecture and stack

```text
backend/
  build.gradle.kts             Build lifecycle and repository formatting
  settings.gradle.kts          Module and dependency repository declarations
  gradlew / gradlew.bat        Pinned Gradle wrapper
  financial-engine/           Pure Java library and tests
  job-store/                  PostgreSQL entities, migrations, and transactional claims/results
  api-service/                Spring Boot HTTP API and local scheduled execution
docs/
  architecture/               Boundaries and calculation conventions
  adr/                        Accepted and proposed decisions
  development/                Validation and contribution workflow
  roadmap/                    Milestones and acceptance criteria
.github/                      Java CI and PR template
```

The engine depends only on the Java standard library at runtime. Future API and
worker applications will call this same engine, keeping calculations independent
of Spring Boot, PostgreSQL, RabbitMQ, and deployment infrastructure. See the
[architecture](docs/architecture/overview.md) and [ADRs](docs/adr/002-gradle-multi-module.md).

The current stack is Java 21, Gradle 9.6.1 with Kotlin DSL, JUnit 5.14.4 for the engine,
Spring Boot 4.1.2, Boot-managed JUnit 6 service tests, PostgreSQL/Flyway, Docker Compose,
and Spotless. Boot 3.5 ended OSS support; the [framework ADR](docs/adr/001-java-21-and-spring-boot.md)
explains the supported-version deviation. RabbitMQ, kind/Helm/KEDA, optional Terraform/AWS,
Prometheus/Grafana, and Next.js/TypeScript come in later milestones.

## Local setup and checks

Install a **Java 21 JDK** and set `JAVA_HOME` to it. No global Gradle installation or
cloud credentials are required. The full build requires a running Docker engine for
real PostgreSQL Testcontainers checks; standalone engine tests do not. Initial builds
need network access for Gradle, dependencies, and container images. Tests use deterministic
fixtures and never call market-data APIs.

From the repository root:

```sh
cd backend
./gradlew --version
./gradlew clean build
```

On Windows use `gradlew.bat` in the same directory. Useful focused commands:

```sh
./gradlew :financial-engine:test
./gradlew :job-store:test :api-service:test
./gradlew :api-service:integrationTest
./gradlew spotlessCheck
./gradlew spotlessApply
```

`build` compiles Java 21 code, runs tests, checks formatting, and creates the library
JAR. Compilation uses `-Xlint:all -Werror`; no separate static analyzer is configured.
Test reports are written to `backend/financial-engine/build/reports/tests/test/index.html`.
See [local API setup and HTTP examples](docs/development/local-api.md) to start PostgreSQL
and the API. The same guide covers containerized verification with Docker Desktop's
Windows CLI when a Linux Docker socket is unavailable.

GitHub Actions runs the build for pull requests and pushes to `main` using Java 21,
SHA-pinned actions, Gradle caching, and read-only repository permissions.

## Calculation conventions

Construct prices from decimal strings, such as `new BigDecimal("102.50")`.
Bars require positive OHLC prices, a consistent high/low range, and nonnegative
volume. A series requires one exact symbol and strictly increasing unique dates;
unsorted inputs are rejected. Empty series are allowed with an explicit symbol.

Indicators use raw closing prices. Daily return means `close[t] / close[t-1] - 1`
between observed bars, including gaps in the calendar. The first return and the
first `window - 1` SMA values are `OptionalDouble.empty()`. Missing dates are not
filled. Decimal arithmetic precedes conversion to finite `double` indicator values;
see [precision and edge cases](docs/architecture/calculations.md).

The crossover simulator uses exact decimal comparisons, whole-share sizing, and
completed-close signals executed at the next available open. Open terminal
positions remain marked at the last close. Fees and cash use exact decimal
arithmetic. Annualization uses ACT/365.25; maximum drawdown is a positive loss
magnitude. The engine does not adjust for splits/dividends or model slippage,
spread, or market impact. See the complete execution policies linked above.

For a small deterministic input, see the [sample CSV and schema](datasets/samples/README.md).
An in-process run uses:

```java
var prices = new CsvPriceParser().parse("TEST", reader);
var config = new StrategyConfiguration(10, 50, new BigDecimal("10000"), new BigDecimal("1"));
var result = new MovingAverageBacktester().run(prices, config);
```

These classes reside in `dev.skulker.engine.csv` and `dev.skulker.engine.backtest`;
the caller supplies and closes `reader`. Synthetic tests verify behavior and do
not establish profitability.

## Development roadmap

After API/database verification, introduce reliable distributed workers, local Kubernetes,
observability/benchmarks, optional AWS deployment, and dashboard in that order.
See [milestones and acceptance criteria](docs/roadmap/milestones.md).

Use short-lived feature branches and small Conventional Commits. Commits, pushes,
PR creation, and merges require explicit authorization. See [development workflow
and manual branch-protection settings](docs/development/workflow.md). Branch
protection has not been verified or enabled by this implementation.

## Cost and licensing

The current library needs no paid market data or cloud resources. Local-first
development is the default. Any future AWS deployment requires explicit approval,
a cost estimate, and a teardown verification plan.

No project license has been selected yet.
