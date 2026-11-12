package dev.skulker.store;

import java.time.Instant;
import java.util.UUID;

/**
 * A claimed publication intent; attempts count publication claims, including crashed publishers.
 */
public record OutboxRecord(
    UUID id,
    UUID jobId,
    OutboxKind kind,
    String failureCode,
    int attempts,
    UUID leaseToken,
    Instant leaseUntil) {}
