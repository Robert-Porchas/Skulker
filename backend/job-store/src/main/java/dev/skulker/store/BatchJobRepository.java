package dev.skulker.store;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BatchJobRepository extends JpaRepository<BatchJobEntity, UUID> {
  List<BatchJobEntity> findByBatchIdOrderByPosition(UUID batchId);

  @Query(
      value =
          """
      SELECT j.id AS id, j.status AS status
      FROM batch_jobs b JOIN jobs j ON j.id = b.job_id
      WHERE b.batch_id = :batchId ORDER BY b.position
      """,
      nativeQuery = true)
  List<BatchStatusProjection> statuses(@Param("batchId") UUID batchId);
}
