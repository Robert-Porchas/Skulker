package dev.skulker.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "batch_jobs")
class BatchJobEntity {
  @Id UUID id;

  @Column(name = "batch_id", nullable = false)
  UUID batchId;

  @Column(name = "job_id", nullable = false)
  UUID jobId;

  @Column(name = "position", nullable = false)
  int position;

  protected BatchJobEntity() {}

  BatchJobEntity(UUID batchId, UUID jobId, int position) {
    this.id = UUID.randomUUID();
    this.batchId = batchId;
    this.jobId = jobId;
    this.position = position;
  }
}
