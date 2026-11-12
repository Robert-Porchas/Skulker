package dev.skulker.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface JobRepository extends JpaRepository<JobEntity, UUID> {
  @Query(
      value = "SELECT * FROM jobs ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset",
      nativeQuery = true)
  List<JobEntity> list(@Param("limit") int limit, @Param("offset") int offset);

  @Query(
      value =
          """
          SELECT * FROM jobs
          WHERE created_at <= :now
            AND (status = 'PENDING' OR (status = 'RUNNING' AND lease_until <= :now))
            AND attempts < 3
          ORDER BY created_at, id
          LIMIT 1 FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  Optional<JobEntity> lockNext(@Param("now") Instant now);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE jobs
          SET status = 'FAILED', failure_code = 'MAX_ATTEMPTS_EXCEEDED',
              lease_token = NULL, lease_until = NULL, updated_at = :now
          WHERE status = 'RUNNING' AND lease_until <= :now AND attempts >= 3
          """,
      nativeQuery = true)
  int failExhausted(@Param("now") Instant now);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE jobs
          SET status = 'SUCCEEDED', result_json = CAST(:resultJson AS jsonb), failure_code = NULL,
              lease_token = NULL, lease_until = NULL, updated_at = :now
          WHERE id = :id AND status = 'RUNNING' AND lease_token = :leaseToken
            AND lease_until > :now AND updated_at <= :now
          """,
      nativeQuery = true)
  int complete(
      @Param("id") UUID id,
      @Param("leaseToken") UUID leaseToken,
      @Param("resultJson") String resultJson,
      @Param("now") Instant now);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE jobs
          SET status = 'FAILED', failure_code = :failureCode, result_json = NULL,
              lease_token = NULL, lease_until = NULL, updated_at = :now
          WHERE id = :id AND status = 'RUNNING' AND lease_token = :leaseToken
            AND lease_until > :now AND updated_at <= :now
          """,
      nativeQuery = true)
  int fail(
      @Param("id") UUID id,
      @Param("leaseToken") UUID leaseToken,
      @Param("failureCode") String failureCode,
      @Param("now") Instant now);
}
