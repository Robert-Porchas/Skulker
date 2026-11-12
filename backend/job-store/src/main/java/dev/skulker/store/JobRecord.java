package dev.skulker.store;

import java.time.Instant;
import java.util.UUID;

/** Durable job state. Result, failure, and lease fields are absent when inapplicable. */
public record JobRecord(
    UUID id,
    UUID datasetId,
    JobStatus status,
    String configurationJson,
    String resultJson,
    String failureCode,
    Instant createdAt,
    Instant updatedAt,
    UUID leaseToken,
    Instant leaseUntil,
    int attempts) {}
