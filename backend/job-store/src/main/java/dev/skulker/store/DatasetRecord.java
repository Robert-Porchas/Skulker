package dev.skulker.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A dataset and its exact uploaded UTF-8 CSV representation, committed in one database row. */
public record DatasetRecord(
    UUID id,
    String symbol,
    String csv,
    String sha256,
    int barCount,
    LocalDate startDate,
    LocalDate endDate,
    Instant createdAt) {}
