package dev.skulker.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox")
class OutboxEntity {
  @Id UUID id;

  @Column(name = "job_id", nullable = false)
  UUID jobId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  OutboxKind kind;

  @Column(name = "failure_code", length = 128)
  String failureCode;

  @Column(name = "job_attempt", nullable = false)
  int jobAttempt;

  @Column(nullable = false)
  int attempts;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "available_at", nullable = false)
  Instant availableAt;

  @Column(name = "last_attempt_at")
  Instant lastAttemptAt;

  @Column(name = "lease_token")
  UUID leaseToken;

  @Column(name = "lease_until")
  Instant leaseUntil;

  @Column(name = "published_at")
  Instant publishedAt;

  @Column(name = "failed_at")
  Instant failedAt;

  protected OutboxEntity() {}

  OutboxEntity(
      UUID jobId,
      OutboxKind kind,
      String failureCode,
      int jobAttempt,
      Instant now,
      Instant availableAt) {
    this.id = UUID.randomUUID();
    this.jobId = jobId;
    this.kind = kind;
    this.failureCode = failureCode;
    this.jobAttempt = jobAttempt;
    this.createdAt = now;
    this.availableAt = availableAt;
  }

  OutboxRecord snapshot() {
    return new OutboxRecord(id, jobId, kind, failureCode, attempts, leaseToken, leaseUntil);
  }
}
