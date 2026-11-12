package dev.skulker.domain;

import java.util.Objects;
import java.util.UUID;

/** Versioned transport reference; PostgreSQL retains the configuration, source data, and state. */
public record BacktestTaskMessage(UUID jobId, int schemaVersion) {
  public BacktestTaskMessage {
    Objects.requireNonNull(jobId, "jobId must not be null");
    if (schemaVersion != 1) {
      throw new IllegalArgumentException("unsupported task schema version: " + schemaVersion);
    }
  }

  public static BacktestTaskMessage of(UUID jobId) {
    return new BacktestTaskMessage(jobId, 1);
  }
}
