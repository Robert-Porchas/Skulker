package dev.skulker.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Dataset metadata for listings; deliberately excludes the stored CSV content. */
public record DatasetSummary(
    UUID id,
    String symbol,
    String sha256,
    int barCount,
    LocalDate startDate,
    LocalDate endDate,
    Instant createdAt) {}
