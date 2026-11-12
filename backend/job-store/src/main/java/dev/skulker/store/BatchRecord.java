package dev.skulker.store;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable parent identity and ordered leaf job identities; leaf jobs own execution state. */
public record BatchRecord(UUID id, Instant createdAt, List<UUID> jobIds) {
  public BatchRecord {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    jobIds = List.copyOf(Objects.requireNonNull(jobIds, "jobIds must not be null"));
  }
}
