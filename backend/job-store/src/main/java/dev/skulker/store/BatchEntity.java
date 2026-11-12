package dev.skulker.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "batches")
class BatchEntity {
  @Id UUID id;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  protected BatchEntity() {}

  BatchEntity(UUID id, Instant createdAt) {
    this.id = id;
    this.createdAt = createdAt;
  }
}
