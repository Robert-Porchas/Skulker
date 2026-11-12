package dev.skulker.store;

import java.util.Objects;
import java.util.UUID;

/** One independently executable backtest in a parameter batch. */
public record JobSpecification(UUID datasetId, String configurationJson) {
  public JobSpecification {
    Objects.requireNonNull(datasetId, "datasetId must not be null");
    Objects.requireNonNull(configurationJson, "configurationJson must not be null");
    if (configurationJson.isBlank()) {
      throw new IllegalArgumentException("configurationJson must not be blank");
    }
  }
}
