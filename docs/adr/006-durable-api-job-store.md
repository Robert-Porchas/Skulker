# ADR-006: Durable datasets and local asynchronous execution

Status: **Accepted** for Milestone 2.

## Decision

Introduce `job-store` for PostgreSQL persistence, Flyway migrations, and short transactional
dataset/job operations. `api-service` owns HTTP validation and the local scheduled job runner.
The financial engine remains infrastructure-independent.

Store immutable original CSV and registration metadata together in PostgreSQL. This is a
bounded local object-storage implementation: registration has no filesystem/database dual-write
window. The API limits upload bytes, bar count, and numeric precision. Later object storage can
replace the CSV column through a concrete storage adapter when cloud datasets are introduced.

Jobs snapshot their configuration and store results as JSONB, preserving decimal values and
immutable history without imposing arbitrary NUMERIC scale defaults. Dataset/job identifiers,
foreign keys, states, timestamps, retry counts, and lease fields remain relational. Normalized
trade analytics tables should be introduced when actual cross-result query requirements justify them.

Submission commits `PENDING` before returning HTTP 202. A scheduled local runner claims one
pending or expired job using `FOR UPDATE SKIP LOCKED`, a fresh fencing token, and a bounded lease.
Computation takes place outside the claim transaction. Results and `SUCCEEDED` commit atomically
only for the current unexpired token; stale processors cannot overwrite committed results.
Expired claims recover with a bounded attempt count. Failure details exposed by HTTP use safe codes.

## Consequences

The database is authoritative even before RabbitMQ exists. There is no in-memory-only work queue
and no database-to-broker atomicity problem yet. Milestone 3 will introduce independent tasks,
transport contracts, RabbitMQ delivery, and an outbox where database publication requires it.
The local runner is replaced or disabled when dedicated workers own execution.

The API is a local research service without user accounts; it binds loopback by default.
Public multi-user hosting requires an explicit authentication and authorization design before exposure.
