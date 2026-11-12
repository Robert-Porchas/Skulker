# Local API development

Requires JDK 21 and a running Docker engine. The engine-only module still builds without Docker.
API/service tests use JUnit 6 as required by Spring Framework 7; financial-engine tests remain JUnit 5.

## Start PostgreSQL, RabbitMQ, API, and worker

From the repository root, choose a local database password without storing it in Git:

```bash
read -rsp 'Local database password: ' SKULKER_DB_PASSWORD
export SKULKER_DB_PASSWORD
read -rsp 'Local RabbitMQ password: ' SKULKER_RABBIT_PASSWORD
export SKULKER_RABBIT_PASSWORD
export SKULKER_RABBIT_USERNAME=skulker
docker compose up -d --wait
cd backend
./gradlew --parallel --max-workers=2 :api-service:bootRun :worker-service:bootRun
```

Keep the same password when reusing the database volume: the PostgreSQL image applies its
initialization environment only when the data directory is empty. Do not delete an existing
volume to resolve a password mismatch without deciding to discard its data.

The API binds `127.0.0.1:8080`; worker health binds `127.0.0.1:8081`. Health endpoints include `/actuator/health`,
`/actuator/health/liveness`, and `/actuator/health/readiness`. Logs use ECS JSON.
There are no user accounts; keep this research API private. HTTP validation errors use ProblemDetail.

Optional environment overrides are `SKULKER_DATABASE_URL`, `SKULKER_DATABASE_USERNAME`,
`SERVER_ADDRESS`, `SERVER_PORT`, `SKULKER_LOCAL_JOBS_ENABLED`, `SKULKER_JOB_POLL_DELAY`, and `LOG_FORMAT`.
RabbitMQ uses `SKULKER_RABBIT_HOST`, `SKULKER_RABBIT_PORT`, `SKULKER_RABBIT_USERNAME`, and
`SKULKER_RABBIT_PASSWORD`. The local M2 executor is disabled by default; dedicated workers
own execution. Both applications may dispatch outbox work safely through leased claims.
`SKULKER_MESSAGING_POLL_DELAY`, `SKULKER_WORKER_LEASE_DURATION`,
`SKULKER_WORKER_RETRY_BACKOFF`, and `SKULKER_WORKER_CONCURRENCY` configure bounded execution.
Changing `SERVER_ADDRESS` exposes the unauthenticated API more broadly; public hosting needs
authentication and authorization first.

## HTTP workflow

From another shell at the repository root:

```bash
curl -f -F symbol=TEST -F file=@datasets/samples/ohlcv.csv http://localhost:8080/api/datasets
curl -f http://localhost:8080/api/datasets
curl -f -H 'Content-Type: application/json' \
  -d '{"datasetId":"REPLACE_WITH_DATASET_UUID","shortWindow":2,"longWindow":3,"initialCapital":10000,"transactionFee":1}' \
  http://localhost:8080/api/jobs
```

Submission returns HTTP 202 and a job UUID. Poll `/api/jobs/{id}`; a confirmed outbox publication
and distributed worker process the durable task. Retrieve `/api/jobs/{id}/result` after `SUCCEEDED`
(HTTP 409 before success).
Datasets have metadata at `/api/datasets/{id}` and original CSV at `/api/datasets/{id}/csv`.
Lists accept `limit` (1–100, default 50) and `offset` (nonnegative).

Uploads require strict UTF-8 and the documented CSV schema. API-specific limits: 2 MiB per file,
10,000 bars, 512 characters per physical row, symbol `[A-Za-z0-9._-]{1,32}`, and price precision
at most 28 with scale at most 12. Empty datasets are rejected. Strategy windows are at most
10,000; capital/fee fields permit 24 integral digits and eight fractional digits. These service
limits do not narrow the standalone engine's decimal model.

## Parameter sweeps and recovery

`POST /api/batches` accepts `datasetIds`, `shortWindows`, `longWindows`, `initialCapital`,
and `transactionFee`. Supply up to 16 distinct datasets and 32 distinct windows in each list.
Only short-window < long-window pairs become tasks; the total must be 1–100 valid combinations.
All jobs/outbox entries and parent membership commit atomically. Example request body:

```json
{"datasetIds":["REPLACE_WITH_DATASET_UUID"],"shortWindows":[2,3],"longWindows":[3,5],"initialCapital":10000,"transactionFee":1}
```

Poll `/api/batches/{id}` for aggregate state/counts and independent job IDs. Per-job result retrieval
remains unchanged. Batch status uses actual task states and does not invent within-task percentages.

The durable queues are `skulker.backtests` and `skulker.backtests.dead`. RabbitMQ management is
available only at `127.0.0.1:15672` with the configured local credentials. Processing retries use
durable delayed outbox entries, with three task attempts; publisher retries back off to 60 seconds
and stop after ten attempts. Lease expiry recovers crashed tasks, while fencing prevents an old
worker from overwriting a new result. Unknown/invalid task references go to the dead-letter route.

The dead-letter queue is for diagnosis. Inspect authoritative job state and failure codes before
replaying anything. There is no automatic operator replay or cancellation API in this milestone.
Keep host clocks synchronized for lease timing; financial calculations use no runtime clock.

## Verification

From `backend/`, `./gradlew clean build` runs all checks, including real PostgreSQL/RabbitMQ Testcontainers
tests. Docker must be reachable by the Java process; missing Docker fails integration checks rather
than silently skipping them. `./gradlew :financial-engine:build :job-store:test :api-service:test :shared-domain:test :worker-service:test`
runs the engine and service unit tests without a PostgreSQL container.

For Docker Desktop with a working Windows CLI but unavailable Linux socket, the repository provides
a containerized verification environment:

```bash
DOCKER_COMMAND=docker.exe bash scripts/verify-in-docker.sh
```

The helper streams source into a SHA-pinned Java 21 image and gives the test container access to
the local Docker socket so Testcontainers can launch disposable PostgreSQL containers. It uses a
named Gradle cache volume and copies API/worker test XML into `backend/build/docker-verification/`.
The verification container is removed automatically; Testcontainers owns its database cleanup.
This is a local test harness, not a production deployment image.

`docker compose stop` stops the local database while retaining data. `docker compose down` removes
its container/network while retaining the named data volume. Delete that volume only when
explicitly choosing to discard local datasets and results.
