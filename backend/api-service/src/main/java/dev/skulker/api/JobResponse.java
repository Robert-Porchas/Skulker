package dev.skulker.api;

import dev.skulker.store.JobRecord;
import dev.skulker.store.JobStatus;
import java.time.Instant;
import java.util.UUID;

/** Public status projection deliberately omits execution leases and stored JSON. */
public record JobResponse(
    UUID id,
    UUID datasetId,
    JobStatus status,
    Instant createdAt,
    Instant updatedAt,
    String failureCode) {
  static JobResponse from(JobRecord job) {
    return new JobResponse(
        job.id(),
        job.datasetId(),
        job.status(),
        job.createdAt(),
        job.updatedAt(),
        job.failureCode());
  }
}
