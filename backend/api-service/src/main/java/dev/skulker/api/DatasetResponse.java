package dev.skulker.api;

import dev.skulker.store.DatasetRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Public dataset metadata; the stored source content is retrieved separately. */
public record DatasetResponse(
    UUID id,
    String symbol,
    String sha256,
    int barCount,
    LocalDate startDate,
    LocalDate endDate,
    Instant createdAt) {
  static DatasetResponse from(DatasetRecord dataset) {
    return new DatasetResponse(
        dataset.id(),
        dataset.symbol(),
        dataset.sha256(),
        dataset.barCount(),
        dataset.startDate(),
        dataset.endDate(),
        dataset.createdAt());
  }
}
