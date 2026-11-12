package dev.skulker.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DistributedStoreValidationTest {
  private static final Instant NOW = Instant.parse("2024-01-02T00:00:00Z");
  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final DatasetRepository datasets = mock(DatasetRepository.class);
  private final JobRepository jobs = mock(JobRepository.class);
  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final BatchRepository batches = mock(BatchRepository.class);
  private final BatchJobRepository batchJobs = mock(BatchJobRepository.class);
  private final JobStore store =
      new JobStore(datasets, jobs, outbox, batches, batchJobs, mock(EntityManager.class));

  @Test
  void batchSpecificationRequiresIdentityAndNonblankConfiguration() {
    assertThrows(NullPointerException.class, () -> new JobSpecification(null, "{}"));
    assertThrows(NullPointerException.class, () -> new JobSpecification(ID, null));
    assertThrows(IllegalArgumentException.class, () -> new JobSpecification(ID, " "));
  }

  @Test
  void rejectsEmptyOversizedAndNullContainingBatchBeforeDatabaseWrites() {
    assertThrows(IllegalArgumentException.class, () -> store.createBatch(List.of(), NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.createBatch(Collections.nCopies(101, new JobSpecification(ID, "{}")), NOW));
    assertThrows(
        NullPointerException.class, () -> store.createBatch(Collections.singletonList(null), NOW));
    assertThrows(NullPointerException.class, () -> store.createBatch(null, NOW));
    verifyNoInteractions(datasets, jobs, outbox, batches, batchJobs);
  }

  @Test
  void batchResponseDefensivelyCopiesOrderedLeafIdentities() {
    List<UUID> source = new ArrayList<>(List.of(ID));
    BatchRecord batch = new BatchRecord(ID, NOW, source);
    source.clear();
    assertEquals(List.of(ID), batch.jobIds());
    assertThrows(UnsupportedOperationException.class, () -> batch.jobIds().clear());
  }

  @Test
  void negativePublicationAndExecutionBackoffsNeverTouchDatabase() {
    Duration negative = Duration.ofSeconds(-1);
    assertThrows(
        IllegalArgumentException.class, () -> store.retryPublication(ID, ID, NOW, negative));
    assertThrows(
        IllegalArgumentException.class, () -> store.retryOrFail(ID, ID, "FAILED", NOW, negative));
    verifyNoInteractions(datasets, jobs, outbox, batches, batchJobs);
  }

  @Test
  void invalidLeaseCannotClaimTaskOrOutbox() {
    assertThrows(IllegalArgumentException.class, () -> store.claimJob(ID, NOW, Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> store.claimOutbox(NOW, Duration.ofNanos(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.claimOutbox(NOW, Duration.ofSeconds(Long.MAX_VALUE)));
    verifyNoInteractions(datasets, jobs, outbox, batches, batchJobs);
  }

  @Test
  void boundedRecoveryRejectsInvalidLimitBeforeScanningDatabase() {
    assertThrows(IllegalArgumentException.class, () -> store.recoverExpiredJobs(NOW, 0));
    assertThrows(IllegalArgumentException.class, () -> store.recoverExpiredJobs(NOW, 101));
    verifyNoInteractions(datasets, jobs, outbox, batches, batchJobs);
  }

  @Test
  void publicationAcknowledgementsRequireBothIdentityAndOwnership() {
    assertThrows(NullPointerException.class, () -> store.markPublished(null, ID, NOW));
    assertThrows(NullPointerException.class, () -> store.markPublished(ID, null, NOW));
    assertThrows(
        NullPointerException.class, () -> store.retryPublication(ID, null, NOW, Duration.ZERO));
    assertThrows(
        NullPointerException.class, () -> store.claimJob(null, NOW, Duration.ofMinutes(1)));
    verifyNoInteractions(datasets, jobs, outbox, batches, batchJobs);
  }

  @Test
  void retryFailureCodesCannotStoreArbitraryExceptionText() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.retryOrFail(ID, ID, "sensitive exception message", NOW, Duration.ZERO));
    verifyNoInteractions(datasets, jobs, outbox, batches, batchJobs);
  }
}
