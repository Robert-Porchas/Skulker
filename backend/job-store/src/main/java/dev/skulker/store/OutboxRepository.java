package dev.skulker.store;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {
  @Query(
      value =
          """
      SELECT * FROM outbox
      WHERE published_at IS NULL AND failed_at IS NULL AND available_at <= :now
        AND (lease_token IS NULL OR lease_until <= :now)
        AND attempts < 10
      ORDER BY available_at, created_at, id
      LIMIT 1 FOR UPDATE SKIP LOCKED
      """,
      nativeQuery = true)
  Optional<OutboxEntity> lockNext(@Param("now") Instant now);

  @Query(
      value =
          """
      SELECT * FROM outbox
      WHERE published_at IS NULL AND failed_at IS NULL AND attempts >= 10
        AND (lease_token IS NULL OR lease_until <= :now)
      ORDER BY created_at, id
      LIMIT 1 FOR UPDATE SKIP LOCKED
      """,
      nativeQuery = true)
  Optional<OutboxEntity> lockExhausted(@Param("now") Instant now);

  @Query(
      value =
          """
      SELECT * FROM outbox
      WHERE id = :id AND lease_token = :leaseToken AND lease_until > :now
        AND last_attempt_at <= :now AND published_at IS NULL AND failed_at IS NULL
      FOR UPDATE
      """,
      nativeQuery = true)
  Optional<OutboxEntity> lockOwned(
      @Param("id") UUID id, @Param("leaseToken") UUID leaseToken, @Param("now") Instant now);
}
