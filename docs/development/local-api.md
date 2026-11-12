# Local API development

Requires JDK 21 and a running Docker engine. The engine-only module still builds without Docker.
API/service tests use JUnit 6 as required by Spring Framework 7; financial-engine tests remain JUnit 5.

## Start PostgreSQL and the API

From the repository root, choose a local database password without storing it in Git:

```bash
read -rsp 'Local database password: ' SKULKER_DB_PASSWORD
export SKULKER_DB_PASSWORD
docker compose up -d --wait
cd backend
./gradlew :api-service:bootRun
```

Keep the same password when reusing the database volume: the PostgreSQL image applies its
initialization environment only when the data directory is empty. Do not delete an existing
volume to resolve a password mismatch without deciding to discard its data.

The API binds `127.0.0.1:8080`. Health endpoints include `/actuator/health`,
`/actuator/health/liveness`, and `/actuator/health/readiness`. Logs use ECS JSON.
There are no user accounts; keep this research API private. HTTP validation errors use ProblemDetail.

Optional environment overrides are `SKULKER_DATABASE_URL`, `SKULKER_DATABASE_USERNAME`,
`SERVER_ADDRESS`, `SERVER_PORT`, `SKULKER_LOCAL_JOBS_ENABLED`, `SKULKER_JOB_POLL_DELAY`, and `LOG_FORMAT`.
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

Submission returns HTTP 202 and a job UUID. Poll `/api/jobs/{id}`; a local scheduled runner
claims pending jobs. Retrieve `/api/jobs/{id}/result` after `SUCCEEDED` (HTTP 409 before success).
Datasets have metadata at `/api/datasets/{id}` and original CSV at `/api/datasets/{id}/csv`.
Lists accept `limit` (1–100, default 50) and `offset` (nonnegative).

Uploads require strict UTF-8 and the documented CSV schema. API-specific limits: 2 MiB per file,
10,000 bars, 512 characters per physical row, symbol `[A-Za-z0-9._-]{1,32}`, and price precision
at most 28 with scale at most 12. Empty datasets are rejected. Strategy windows are at most
10,000; capital/fee fields permit 24 integral digits and eight fractional digits. These service
limits do not narrow the standalone engine's decimal model.

## Verification

From `backend/`, `./gradlew clean build` runs all checks, including real PostgreSQL Testcontainers
tests. Docker must be reachable by the Java process; missing Docker fails integration checks rather
than silently skipping them. `./gradlew :financial-engine:build :job-store:test :api-service:test`
runs the engine and service unit tests without a PostgreSQL container.

For Docker Desktop with a working Windows CLI but unavailable Linux socket, the repository provides
a containerized verification environment:

```bash
DOCKER_COMMAND=docker.exe bash scripts/verify-in-docker.sh
```

The helper streams source into a SHA-pinned Java 21 image and gives the test container access to
the local Docker socket so Testcontainers can launch disposable PostgreSQL containers. It uses a
named Gradle cache volume and copies API test XML into `backend/build/docker-verification/`.
The verification container is removed automatically; Testcontainers owns its database cleanup.
This is a local test harness, not a production deployment image.

`docker compose stop` stops the local database while retaining data. `docker compose down` removes
its container/network while retaining the named data volume. Delete that volume only when
explicitly choosing to discard local datasets and results.
