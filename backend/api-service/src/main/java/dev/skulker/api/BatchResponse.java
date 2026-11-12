package dev.skulker.api;

import dev.skulker.store.JobStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BatchResponse(
    UUID id,
    JobStatus status,
    Instant createdAt,
    List<UUID> jobIds,
    int total,
    int succeeded,
    int failed) {
  public BatchResponse {
    jobIds = List.copyOf(jobIds);
  }
}
