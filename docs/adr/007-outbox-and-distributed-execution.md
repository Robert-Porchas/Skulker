# ADR-007: Transactional publication and fenced worker execution

Status: **Accepted** for Milestone 3; implementation is subject to its verification gate.

## Context

Creating a durable job and publishing a RabbitMQ message are separate operations.
Neither a database transaction around the HTTP request nor broker persistence alone
closes the failure window between those operations. Workers can crash after claiming
work, during calculation, or between result commit and message acknowledgment.

## Decision

Insert a task outbox record in the same PostgreSQL transaction as job creation.
Publish a bounded schema-versioned message containing only the immutable job identifier.
Use durable direct exchanges/queues, persistent messages, mandatory routing, correlated
publisher confirms, and returned-message checks. Mark outbox publication only after
successful routing confirmation. A crash before that mark can publish a duplicate.

Each leaf job is one independent backtest task; a parameter-sweep batch is its parent
analysis. Batch creation atomically creates all valid dataset/window tasks and their
outbox entries. Existing single-job submission remains compatible. The database is
authoritative for configurations, datasets, task states, and results.

Workers claim by identifier using an expiring lease and unique fencing token.
Calculation runs outside database transactions. Completion persists result and success
atomically only for the current unexpired owner. A duplicate for a terminal or active
task is acknowledged; an expired-lease recovery transaction creates fresh durable
publication work so acknowledging an active duplicate cannot strand a crashed task.

Processing failures either schedule a new outbox attempt after bounded backoff or
terminate the task after three execution attempts and enqueue a dead-letter publication.
Expired third attempts also fail and publish a dead letter. Malformed broker messages
are rejected to the broker's dead-letter route. Publication itself has a bounded lease,
backoff, and attempt budget; exhaustion cannot silently leave a never-started job pending.

Manual consumer acknowledgments occur after durable completion/retry/failure decisions.
Database errors retain or requeue broker deliveries; local connection retries use bounded
backoff. Crashes after result commit are safe because redelivery observes terminal state.

## Consequences

Delivery is **at least once**, with effectively once-only committed results per task.
No exactly-once transport guarantee is claimed. Outbox and dead-letter deliveries may
duplicate and operational tooling must inspect task state before replaying failed work.

The local database-polled runner is disabled by default once dedicated workers own
execution. Independent workers reuse the same engine and store; messaging classes do
not enter the financial library. Single-broker local durability is not a highly available
RabbitMQ cluster, and the dead-letter queue is not authoritative task storage.
