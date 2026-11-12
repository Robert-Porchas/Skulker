# ADR-004: RabbitMQ for future task transport

Status: **Proposed** for Milestone 3; not implemented.

## Context

Independent symbol/strategy-parameter combinations should execute concurrently, with safe recovery after delivery duplication and worker failure.

## Proposed decision

Use RabbitMQ with durable queues, persistent task messages, explicit acknowledgments, bounded retries, and dead-letter handling. Keep PostgreSQL authoritative for task state and committed results. Use unique task identifiers and idempotent persistence rather than claiming exactly-once message delivery.

Evaluate a transactional outbox for the failure window between creating database tasks and publishing messages. Define retry and lease/recovery semantics before implementation.

## Consequences

This adds a broker and cross-system failure modes. Integration tests must exercise duplicate delivery, crashes before/after result commit, and exhausted retries. None of these dependencies or guarantees is part of the current engine.
