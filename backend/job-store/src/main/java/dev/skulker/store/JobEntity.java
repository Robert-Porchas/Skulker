package dev.skulker.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "jobs")
class JobEntity {
  @Id UUID id;

  @Column(name = "dataset_id", nullable = false)
  UUID datasetId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  JobStatus status;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "configuration_json", nullable = false, columnDefinition = "jsonb")
  String configurationJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "result_json", columnDefinition = "jsonb")
  String resultJson;

  @Column(name = "failure_code", length = 128)
  String failureCode;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Column(name = "available_at", nullable = false)
  Instant availableAt;

  @Column(name = "lease_token")
  UUID leaseToken;

  @Column(name = "lease_until")
  Instant leaseUntil;

  @Column(nullable = false)
  int attempts;

  protected JobEntity() {}

  JobEntity(UUID id, UUID datasetId, String configurationJson, Instant now) {
    this.id = id;
    this.datasetId = datasetId;
    this.status = JobStatus.PENDING;
    this.configurationJson = configurationJson;
    this.createdAt = now;
    this.updatedAt = now;
    this.availableAt = now;
  }

  JobRecord snapshot() {
    return new JobRecord(
        id,
        datasetId,
        status,
        configurationJson,
        resultJson,
        failureCode,
        createdAt,
        updatedAt,
        leaseToken,
        leaseUntil,
        attempts);
  }
}
