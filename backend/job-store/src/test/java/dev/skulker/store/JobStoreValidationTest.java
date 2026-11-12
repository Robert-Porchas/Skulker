package dev.skulker.store;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class JobStoreValidationTest {
  private static final Instant NOW = Instant.parse("2024-01-02T00:00:00Z");
  private static final LocalDate DATE = LocalDate.of(2024, 1, 2);
  private static final String HASH = "a".repeat(64);
  private final DatasetRepository datasets = mock(DatasetRepository.class);
  private final JobRepository jobs = mock(JobRepository.class);
  private final OutboxRepository outbox = mock(OutboxRepository.class);
  private final BatchRepository batches = mock(BatchRepository.class);
  private final BatchJobRepository batchJobs = mock(BatchJobRepository.class);
  private final EntityManager entityManager = mock(EntityManager.class);
  private final JobStore store =
      new JobStore(datasets, jobs, outbox, batches, batchJobs, entityManager);

  @ParameterizedTest
  @CsvSource({"0,0", "101,0", "1,-1", "-1,1"})
  void rejectsInvalidPaginationBeforeReadingDatabase(int limit, int offset) {
    assertThrows(IllegalArgumentException.class, () -> store.listDatasets(limit, offset));
    assertThrows(IllegalArgumentException.class, () -> store.listJobs(limit, offset));
    assertThrows(IllegalArgumentException.class, () -> store.listDatasetSummaries(limit, offset));
    assertThrows(IllegalArgumentException.class, () -> store.listJobSummaries(limit, offset));
    verifyNoInteractions(datasets, jobs, entityManager);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", " TEST", "TEST "})
  void rejectsAmbiguousDatasetSymbol(String symbol) {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.saveDataset(symbol, "csv", HASH, 1, DATE, DATE, NOW));
    verifyNoInteractions(datasets, jobs);
  }

  @Test
  void rejectsOversizedSymbol() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.saveDataset("X".repeat(65), "csv", HASH, 1, DATE, DATE, NOW));
    verifyNoInteractions(datasets, jobs);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "a",
        "ABCDEF",
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
      })
  void rejectsInvalidHash(String hash) {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.saveDataset("TEST", "csv", hash, 1, DATE, DATE, NOW));
    verifyNoInteractions(datasets, jobs);
  }

  @Test
  void rejectsEmptyDatasetOrBackwardsDateRange() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.saveDataset("TEST", "csv", HASH, 0, DATE, DATE, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.saveDataset("TEST", "csv", HASH, 1, DATE.plusDays(1), DATE, NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.saveDataset("TEST", "\n ", HASH, 1, DATE, DATE, NOW));
    verifyNoInteractions(datasets, jobs);
  }

  @Test
  void rejectsMissingDatasetFields() {
    assertThrows(
        NullPointerException.class, () -> store.saveDataset(null, "csv", HASH, 1, DATE, DATE, NOW));
    assertThrows(
        NullPointerException.class,
        () -> store.saveDataset("TEST", null, HASH, 1, DATE, DATE, NOW));
    assertThrows(
        NullPointerException.class,
        () -> store.saveDataset("TEST", "csv", HASH, 1, null, DATE, NOW));
    assertThrows(
        NullPointerException.class,
        () -> store.saveDataset("TEST", "csv", HASH, 1, DATE, DATE, null));
    verifyNoInteractions(datasets, jobs);
  }

  @Test
  void rejectsInvalidLeaseDurationBeforeClaimingAnyRow() {
    assertThrows(IllegalArgumentException.class, () -> store.claimNext(NOW, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> store.claimNext(NOW, Duration.ofSeconds(-1)));
    assertThrows(IllegalArgumentException.class, () -> store.claimNext(NOW, Duration.ofNanos(1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.claimNext(NOW, Duration.ofSeconds(Long.MAX_VALUE)));
    assertThrows(NullPointerException.class, () -> store.claimNext(NOW, null));
    assertThrows(NullPointerException.class, () -> store.claimNext(null, Duration.ofMinutes(1)));
    verifyNoInteractions(datasets, jobs);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "lowercase", "USER ENTERED MESSAGE", "_INVALID", "1INVALID"})
  void failureUsesBoundedMachineCodeInsteadOfArbitraryExceptionText(String failureCode) {
    assertThrows(
        IllegalArgumentException.class,
        () -> store.fail(UUID.randomUUID(), UUID.randomUUID(), failureCode, NOW));
    verifyNoInteractions(datasets, jobs);
  }

  @Test
  void rejectsEmptyConfigurationAndResultBeforeWritingDatabase() {
    assertThrows(
        IllegalArgumentException.class, () -> store.createJob(UUID.randomUUID(), " ", NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.complete(UUID.randomUUID(), UUID.randomUUID(), " ", NOW));
    verifyNoInteractions(datasets, jobs);
  }

  @Test
  void identifiersAreRequiredForReadsAndConditionalWrites() {
    assertThrows(NullPointerException.class, () -> store.findDataset(null));
    assertThrows(NullPointerException.class, () -> store.findJob(null));
    assertThrows(
        NullPointerException.class, () -> store.complete(UUID.randomUUID(), null, "{}", NOW));
    assertThrows(
        NullPointerException.class, () -> store.fail(UUID.randomUUID(), null, "FAILED", NOW));
    verifyNoInteractions(datasets, jobs);
  }
}
