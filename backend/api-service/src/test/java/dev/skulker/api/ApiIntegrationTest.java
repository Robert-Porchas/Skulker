package dev.skulker.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.skulker.store.DatasetRecord;
import dev.skulker.store.JobRecord;
import dev.skulker.store.JobStatus;
import dev.skulker.store.JobStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@Testcontainers
@SpringBootTest(
    properties = {"skulker.jobs.local-enabled=false", "skulker.messaging.enabled=false"})
@AutoConfigureMockMvc
class ApiIntegrationTest {
  private static final String CSV =
      """
      Date,Open,High,Low,Close,Volume
      2024-01-02,3,3,3,3,100
      2024-01-03,2,2,2,2,100
      2024-01-04,1,1,1,1,100
      2024-01-05,2,2,2,2,100
      2024-01-08,10,10,3,3,100
      2024-01-09,2,2,2,2,100
      """;
  private static final String CONFIGURATION =
      """
      {"shortWindow":1,"longWindow":2,"initialCapital":100,"transactionFee":1}
      """;
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Duration LEASE = Duration.ofSeconds(60);

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private MockMvc mvc;
  @Autowired private JsonMapper json;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JobStore store;
  @Autowired private JobRunner runner;

  @BeforeEach
  void clearCommittedData() {
    jdbc.update("DELETE FROM jobs");
    jdbc.update("DELETE FROM batches");
    jdbc.update("DELETE FROM datasets");
  }

  @Test
  void uploadedDatasetRunsThroughPendingJobToPersistedBacktestResult() throws Exception {
    UUID datasetId = uploadDataset();
    String response =
        mvc.perform(
                post("/api/jobs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jobRequest(datasetId, 1, 2)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.datasetId").value(datasetId.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID jobId = UUID.fromString(json.readTree(response).get("id").asString());

    mvc.perform(get("/api/jobs/{id}/result", jobId)).andExpect(status().isConflict());
    mvc.perform(get("/api/jobs/{id}", jobId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"));
    assertTrue(runner.runOne());
    assertFalse(runner.runOne());

    mvc.perform(get("/api/jobs/{id}", jobId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    String result =
        mvc.perform(get("/api/jobs/{id}/result", jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.symbol").value("TEST"))
            .andExpect(jsonPath("$.trades", hasSize(1)))
            .andExpect(jsonPath("$.trades[0].signalDate").value("2024-01-05"))
            .andExpect(jsonPath("$.trades[0].executionDate").value("2024-01-08"))
            .andExpect(jsonPath("$.trades[0].quantity").value(9))
            .andExpect(jsonPath("$.trades[0].price").value(10))
            .andExpect(jsonPath("$.trades[0].fee").value(1))
            .andExpect(jsonPath("$.finalPortfolioValue").value(27))
            .andExpect(jsonPath("$.maximumDrawdown").value(0.73))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertEquals(
        new BigDecimal("27"), json.readTree(result).get("finalPortfolioValue").decimalValue());
    assertEquals(JobStatus.SUCCEEDED, store.findJob(jobId).orElseThrow().status());
    assertTrue(store.findJob(jobId).orElseThrow().resultJson().contains("finalPortfolioValue"));
  }

  @Test
  void singleBarResultPersistsAndRestoresUnavailableAnnualizationThroughHttp() throws Exception {
    String singleBarCsv = "Date,Open,High,Low,Close,Volume\n2024-01-02,10,10,10,10,100\n";
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "single.csv", "text/csv", singleBarCsv.getBytes(StandardCharsets.UTF_8));
    String datasetResponse =
        mvc.perform(multipart("/api/datasets").file(file).param("symbol", "TEST"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID datasetId = UUID.fromString(json.readTree(datasetResponse).get("id").asString());
    String jobResponse =
        mvc.perform(
                post("/api/jobs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jobRequest(datasetId, 1, 2)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID jobId = UUID.fromString(json.readTree(jobResponse).get("id").asString());

    assertTrue(runner.runOne());

    mvc.perform(get("/api/jobs/{id}/result", jobId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.annualizedReturn").isEmpty())
        .andExpect(jsonPath("$.finalPortfolioValue").value(100))
        .andExpect(jsonPath("$.trades", hasSize(0)));
    assertTrue(
        json.readTree(store.findJob(jobId).orElseThrow().resultJson())
            .get("annualizedReturn")
            .isNull());
  }

  @Test
  void metadataListsAndCsvDownloadUsePersistedDataset() throws Exception {
    UUID datasetId = uploadDataset();
    String expectedSha256 =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256").digest(CSV.getBytes(StandardCharsets.UTF_8)));
    mvc.perform(get("/api/datasets/{id}", datasetId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(datasetId.toString()))
        .andExpect(jsonPath("$.symbol").value("TEST"))
        .andExpect(jsonPath("$.sha256").value(expectedSha256))
        .andExpect(jsonPath("$.barCount").value(6))
        .andExpect(jsonPath("$.startDate").value("2024-01-02"))
        .andExpect(jsonPath("$.endDate").value("2024-01-09"));
    mvc.perform(get("/api/datasets").param("limit", "1").param("offset", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id").value(datasetId.toString()));
    mvc.perform(get("/api/datasets/{id}/csv", datasetId))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", containsString("attachment")))
        .andExpect(content().string(CSV));
    assertEquals(CSV, store.findDataset(datasetId).orElseThrow().csv());

    JobRecord job = store.createJob(datasetId, CONFIGURATION, NOW);
    mvc.perform(get("/api/jobs").param("limit", "1").param("offset", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id").value(job.id().toString()));
  }

  @Test
  void invalidCsvReturns422WithoutPersistingDataset() throws Exception {
    MockMultipartFile malformed =
        new MockMultipartFile(
            "file",
            "invalid.csv",
            "text/csv",
            "Date,Close\n2024-01-02,10\n".getBytes(StandardCharsets.UTF_8));
    mvc.perform(multipart("/api/datasets").file(malformed).param("symbol", "TEST"))
        .andExpect(status().is(422))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(422));
    assertTrue(store.listDatasets(100, 0).isEmpty());
  }

  @Test
  void oversizedInvalidUtf8AndHeaderOnlyUploadsNeverPersistDatasets() throws Exception {
    MockMultipartFile oversized =
        new MockMultipartFile("file", "large.csv", "text/csv", new byte[2 * 1024 * 1024 + 1]);
    mvc.perform(multipart("/api/datasets").file(oversized).param("symbol", "TEST"))
        .andExpect(status().is(413));
    MockMultipartFile invalidUtf8 =
        new MockMultipartFile("file", "invalid.csv", "text/csv", new byte[] {(byte) 0xC3, 0x28});
    mvc.perform(multipart("/api/datasets").file(invalidUtf8).param("symbol", "TEST"))
        .andExpect(status().is(422));
    MockMultipartFile emptyDataset =
        new MockMultipartFile(
            "file",
            "empty.csv",
            "text/csv",
            "Date,Open,High,Low,Close,Volume\n".getBytes(StandardCharsets.UTF_8));
    mvc.perform(multipart("/api/datasets").file(emptyDataset).param("symbol", "TEST"))
        .andExpect(status().is(422));
    assertTrue(store.listDatasets(100, 0).isEmpty());
  }

  @Test
  void malformedRequestsAndInvalidConfigurationDoNotCreateJobs() throws Exception {
    UUID datasetId = uploadDataset();
    mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jobRequest(datasetId, 2, 2)))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(multipart("/api/datasets").param("symbol", "TEST"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/jobs").param("limit", "101")).andExpect(status().isBadRequest());
    assertTrue(store.listJobs(100, 0).isEmpty());
  }

  @Test
  void missingResourcesReturn404AndUnknownDatasetCannotCreateJob() throws Exception {
    UUID missing = UUID.fromString("00000000-0000-0000-0000-000000000001");
    mvc.perform(get("/api/datasets/{id}", missing)).andExpect(status().isNotFound());
    mvc.perform(get("/api/datasets/{id}/csv", missing)).andExpect(status().isNotFound());
    mvc.perform(get("/api/jobs/{id}", missing)).andExpect(status().isNotFound());
    mvc.perform(get("/api/jobs/{id}/result", missing)).andExpect(status().isNotFound());
    mvc.perform(
            post("/api/jobs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jobRequest(missing, 1, 2)))
        .andExpect(status().isNotFound());
    assertTrue(store.listJobs(100, 0).isEmpty());
  }

  @Test
  void batchSubmissionCreatesOnlyValidParameterPairsAndAggregatesCompletedJobs() throws Exception {
    UUID datasetId = uploadDataset();
    String request =
        """
        {"datasetIds":["%s"],"shortWindows":[1,2,3],"longWindows":[3],"initialCapital":100,"transactionFee":1}
        """
            .formatted(datasetId);
    String response =
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.jobIds", hasSize(2)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID batchId = UUID.fromString(json.readTree(response).get("id").asString());
    assertEquals(2, store.listJobs(100, 0).size());

    assertTrue(runner.runOne());
    assertTrue(runner.runOne());
    assertFalse(runner.runOne());

    mvc.perform(get("/api/batches/{id}", batchId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.succeeded").value(2))
        .andExpect(jsonPath("$.failed").value(0));
  }

  @Test
  void unknownDatasetMakesBatchSubmissionAtomicWithoutPartialJobs() throws Exception {
    UUID datasetId = uploadDataset();
    UUID missingId = UUID.fromString("00000000-0000-0000-0000-000000000003");
    String request =
        """
        {"datasetIds":["%s","%s"],"shortWindows":[1],"longWindows":[2],"initialCapital":100,"transactionFee":1}
        """
            .formatted(datasetId, missingId);

    mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isNotFound());

    assertTrue(store.listJobs(100, 0).isEmpty());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM batches", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class));
  }

  @Test
  void failedExecutionPersistsSafeFailureAndNeverExposesAResult() throws Exception {
    UUID datasetId = uploadDataset();
    JobRecord invalidJob = store.createJob(datasetId, "{}", NOW);

    assertTrue(runner.runOne());

    mvc.perform(get("/api/jobs/{id}", invalidJob.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.failureCode").value("BACKTEST_FAILED"));
    mvc.perform(get("/api/jobs/{id}/result", invalidJob.id())).andExpect(status().isConflict());
    assertNull(store.findJob(invalidJob.id()).orElseThrow().resultJson());
  }

  @Test
  void twoConcurrentClaimersCannotOwnTheSameJob() throws Exception {
    JobRecord pending = createPendingJob();
    CyclicBarrier simultaneousStart = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Optional<JobRecord>> first =
          executor.submit(
              () -> {
                simultaneousStart.await(30, TimeUnit.SECONDS);
                return store.claimNext(NOW, LEASE);
              });
      Future<Optional<JobRecord>> second =
          executor.submit(
              () -> {
                simultaneousStart.await(30, TimeUnit.SECONDS);
                return store.claimNext(NOW, LEASE);
              });
      List<Optional<JobRecord>> claims =
          List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
      assertEquals(1, claims.stream().filter(Optional::isPresent).count());
      JobRecord owner = claims.stream().flatMap(Optional::stream).findFirst().orElseThrow();
      assertEquals(pending.id(), owner.id());
      assertEquals(JobStatus.RUNNING, owner.status());
      assertEquals(1, store.findJob(pending.id()).orElseThrow().attempts());
    }
  }

  @Test
  void expiredLeaseCannotCommitAndRecoveryRequiresANewOwnershipToken() {
    JobRecord pending = createPendingJob();
    JobRecord first = store.claimNext(NOW, LEASE).orElseThrow();
    Instant expiry = NOW.plus(LEASE);

    assertFalse(store.complete(pending.id(), first.leaseToken(), "{}", expiry));
    assertFalse(store.fail(pending.id(), first.leaseToken(), "STALE", expiry));
    JobRecord recovered = store.claimNext(expiry, LEASE).orElseThrow();
    assertEquals(2, recovered.attempts());
    assertNotEquals(first.leaseToken(), recovered.leaseToken());
    assertFalse(store.complete(pending.id(), first.leaseToken(), "{}", expiry.plusSeconds(1)));
    assertTrue(store.complete(pending.id(), recovered.leaseToken(), "{}", expiry.plusSeconds(1)));
    assertFalse(store.complete(pending.id(), recovered.leaseToken(), "{}", expiry.plusSeconds(2)));
    assertEquals(JobStatus.SUCCEEDED, store.findJob(pending.id()).orElseThrow().status());
  }

  @Test
  void threeExpiredAttemptsBecomeTerminalRatherThanBeingClaimedForever() {
    JobRecord pending = createPendingJob();
    for (int attempt = 1; attempt <= 3; attempt++) {
      Instant claimTime = NOW.plus(LEASE.multipliedBy(attempt - 1L));
      assertEquals(attempt, store.claimNext(claimTime, LEASE).orElseThrow().attempts());
    }

    assertTrue(store.claimNext(NOW.plus(LEASE.multipliedBy(3)), LEASE).isEmpty());
    JobRecord exhausted = store.findJob(pending.id()).orElseThrow();
    assertEquals(JobStatus.FAILED, exhausted.status());
    assertEquals("MAX_ATTEMPTS_EXCEEDED", exhausted.failureCode());
    assertNull(exhausted.resultJson());
    assertNull(exhausted.leaseToken());
  }

  @Test
  void jsonbPreservesExactDecimalResultAndStatusCommitsWithItAtomically() {
    JobRecord pending = createPendingJob();
    JobRecord owner = store.claimNext(NOW, LEASE).orElseThrow();

    assertThrows(
        DataAccessException.class,
        () -> store.complete(pending.id(), owner.leaseToken(), "{invalid", NOW.plusSeconds(1)));
    JobRecord afterInvalidJson = store.findJob(pending.id()).orElseThrow();
    assertEquals(JobStatus.RUNNING, afterInvalidJson.status());
    assertNull(afterInvalidJson.resultJson());
    String exactMoney = "12345678901234567890.00000000000000000001";
    String result = "{\"finalPortfolioValue\":" + exactMoney + "}";
    assertTrue(store.complete(pending.id(), owner.leaseToken(), result, NOW.plusSeconds(2)));

    JobRecord completed = store.findJob(pending.id()).orElseThrow();
    assertEquals(JobStatus.SUCCEEDED, completed.status());
    assertTrue(completed.resultJson().contains(exactMoney));
    assertNull(completed.leaseToken());
    assertNull(completed.leaseUntil());
    assertNull(completed.failureCode());
  }

  @Test
  void failureIsDurableAndOnlyCurrentOwnerCanWriteIt() {
    JobRecord pending = createPendingJob();
    JobRecord owner = store.claimNext(NOW, LEASE).orElseThrow();
    assertFalse(
        store.fail(
            pending.id(), UUID.fromString("00000000-0000-0000-0000-000000000002"), "OTHER", NOW));
    assertTrue(
        store.fail(pending.id(), owner.leaseToken(), "CALCULATION_FAILED", NOW.plusSeconds(1)));

    JobRecord failed = store.findJob(pending.id()).orElseThrow();
    assertEquals(JobStatus.FAILED, failed.status());
    assertEquals("CALCULATION_FAILED", failed.failureCode());
    assertNull(failed.resultJson());
    assertTrue(store.claimNext(NOW.plusSeconds(2), LEASE).isEmpty());
    assertFalse(store.complete(pending.id(), owner.leaseToken(), "{}", NOW.plusSeconds(2)));
  }

  private UUID uploadDataset() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "prices.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8));
    String response =
        mvc.perform(multipart("/api/datasets").file(file).param("symbol", "TEST"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.barCount").value(6))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asString());
  }

  private JobRecord createPendingJob() {
    DatasetRecord dataset =
        store.saveDataset(
            "TEST",
            CSV,
            "a".repeat(64),
            6,
            LocalDate.of(2024, 1, 2),
            LocalDate.of(2024, 1, 9),
            NOW);
    return store.createJob(dataset.id(), CONFIGURATION, NOW);
  }

  private static String jobRequest(UUID datasetId, int shortWindow, int longWindow) {
    return """
        {"datasetId":"%s","shortWindow":%d,"longWindow":%d,"initialCapital":100,"transactionFee":1}
        """
        .formatted(datasetId, shortWindow, longWindow);
  }
}
