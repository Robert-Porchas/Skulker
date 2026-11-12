package dev.skulker.store;

import java.time.Instant;
import java.util.UUID;

/** Publicly useful job state without configurations, results, or execution lease fields. */
public record JobSummary(
    UUID id,
    UUID datasetId,
    JobStatus status,
    String failureCode,
    Instant createdAt,
    Instant updatedAt) {}
