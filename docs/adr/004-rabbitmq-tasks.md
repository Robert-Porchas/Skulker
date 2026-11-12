# ADR-004: RabbitMQ for future task transport

Status: **Accepted** for Milestone 3; checked completion is recorded in the verification log.

## Context

Independent symbol/strategy-parameter combinations should execute concurrently, with safe recovery after delivery duplication and worker failure.

## Decision

Use RabbitMQ with durable queues, persistent task messages, explicit acknowledgments, bounded retries, and dead-letter handling. Keep PostgreSQL authoritative for task state and committed results. Use unique task identifiers and idempotent persistence rather than claiming exactly-once message delivery.

Use a transactional outbox for the failure window between creating database tasks and publishing messages. See [publication and fencing](007-outbox-and-distributed-execution.md) for retry, lease, and recovery semantics.

## Consequences

This adds a broker and cross-system failure modes. Integration tests must exercise duplicate delivery, crashes before/after result commit, and exhausted retries. None of these dependencies or guarantees is part of the current engine.
