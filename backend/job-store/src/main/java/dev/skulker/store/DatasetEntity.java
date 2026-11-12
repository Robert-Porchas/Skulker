package dev.skulker.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "datasets")
class DatasetEntity {
  @Id UUID id;

  @Column(nullable = false, length = 64)
  String symbol;

  @Column(nullable = false, columnDefinition = "text")
  String csv;

  @Column(nullable = false, length = 64)
  String sha256;

  @Column(name = "bar_count", nullable = false)
  int barCount;

  @Column(name = "start_date", nullable = false)
  LocalDate startDate;

  @Column(name = "end_date", nullable = false)
  LocalDate endDate;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  protected DatasetEntity() {}

  DatasetEntity(
      UUID id,
      String symbol,
      String csv,
      String sha256,
      int barCount,
      LocalDate startDate,
      LocalDate endDate,
      Instant createdAt) {
    this.id = id;
    this.symbol = symbol;
    this.csv = csv;
    this.sha256 = sha256;
    this.barCount = barCount;
    this.startDate = startDate;
    this.endDate = endDate;
    this.createdAt = createdAt;
  }

  DatasetRecord snapshot() {
    return new DatasetRecord(id, symbol, csv, sha256, barCount, startDate, endDate, createdAt);
  }
}
