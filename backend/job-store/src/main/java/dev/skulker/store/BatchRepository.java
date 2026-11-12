package dev.skulker.store;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface BatchRepository extends JpaRepository<BatchEntity, UUID> {}
