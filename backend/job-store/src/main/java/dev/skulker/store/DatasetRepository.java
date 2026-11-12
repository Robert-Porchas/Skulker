package dev.skulker.store;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DatasetRepository extends JpaRepository<DatasetEntity, UUID> {
  @Query(
      value = "SELECT * FROM datasets ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset",
      nativeQuery = true)
  List<DatasetEntity> list(@Param("limit") int limit, @Param("offset") int offset);
}
