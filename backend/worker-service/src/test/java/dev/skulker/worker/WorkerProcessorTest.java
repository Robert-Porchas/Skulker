package dev.skulker.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import dev.skulker.store.DatasetRecord;
import dev.skulker.store.JobRecord;
import dev.skulker.store.JobStatus;
import dev.skulker.store.JobStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

class WorkerProcessorTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Duration LEASE = Duration.ofMinutes(5);
  private final JobStore store = mock(JobStore.class);
  private final Channel channel = mock(Channel.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private WorkerProcessor processor;

  @BeforeEach
  void createProcessor() {
    processor =
        new WorkerProcessor(
            store,
            JsonMapper.builder().findAndAddModules().build(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            LEASE,
            Duration.ofSeconds(1),
            registry);
  }

  @AfterEach
  void closeRegistry() {
    registry.close();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "not-json",
        "null",
        "{}",
        "{\"schemaVersion\":1}",
        "{\"jobId\":\"00000000-0000-0000-0000-000000000001\",\"schemaVersion\":99}"
      })
  void poisonMessagesAreRejectedWithoutReadingOrMutatingJobs(String body) throws Exception {
    processor.handle(message(body.getBytes(StandardCharsets.UTF_8)), channel);

    verify(channel).basicReject(42, false);
    verifyNoMoreInteractions(channel);
    verifyNoInteractions(store);
    assertEquals(1, counter("rejected"));
    assertEquals(0, active());
  }

  @Test
  void oversizedMessageIsRejectedBeforeParsingOrAccessingTheDatabase() throws Exception {
    processor.handle(message(new byte[1025]), channel);

    verify(channel).basicReject(42, false);
    verifyNoInteractions(store);
    assertEquals(1, counter("rejected"));
    assertEquals(0, active());
  }

  @ParameterizedTest
  @EnumSource(
      value = JobStatus.class,
      names = {"RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"})
  void busyOrTerminalDuplicateIsAcknowledgedWithoutRecomputingAResult(JobStatus status)
      throws Exception {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    when(store.claimJob(id, NOW, LEASE)).thenReturn(Optional.empty());
    when(store.findJob(id)).thenReturn(Optional.of(job(id, status, NOW)));

    processor.handle(task(id), channel);

    verify(store).claimJob(id, NOW, LEASE);
    verify(store).findJob(id);
    verifyNoMoreInteractions(store);
    verify(channel).basicAck(42, false);
    verifyNoMoreInteractions(channel);
    assertEquals(1, counter("duplicates"));
    assertEquals(0, counter("succeeded"));
    assertEquals(0, active());
  }

  @Test
  void unknownJobIsRejectedToDeadLettersRatherThanAcknowledgedAsADuplicate() throws Exception {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    when(store.claimJob(id, NOW, LEASE)).thenReturn(Optional.empty());
    when(store.findJob(id)).thenReturn(Optional.empty());

    processor.handle(task(id), channel);

    verify(store).claimJob(id, NOW, LEASE);
    verify(store).findJob(id);
    verifyNoMoreInteractions(store);
    verify(channel).basicReject(42, false);
    verifyNoMoreInteractions(channel);
    assertEquals(1, counter("rejected"));
    assertEquals(0, counter("duplicates"));
    assertEquals(0, active());
  }

  @ParameterizedTest
  @EnumSource(
      value = JobStatus.class,
      names = {"PENDING", "QUEUED"})
  void notYetClaimablePendingOrQueuedDeliveryIsRequeuedWithoutAcknowledgment(JobStatus status)
      throws Exception {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    when(store.claimJob(id, NOW, LEASE)).thenReturn(Optional.empty());
    when(store.findJob(id)).thenReturn(Optional.of(job(id, status, NOW.plusSeconds(60))));

    processor.handle(task(id), channel);

    verify(store, times(3)).claimJob(id, NOW, LEASE);
    verify(store, times(3)).findJob(id);
    verifyNoMoreInteractions(store);
    verify(channel).basicNack(42, false, true);
    verifyNoMoreInteractions(channel);
    assertEquals(0, counter("retried"));
    assertEquals(0, counter("succeeded"));
    assertEquals(0, active());
  }

  @Test
  void durableSuccessCountsOnlyAfterCommitAndActiveReturnsToZero() throws Exception {
    JobRecord job = prepareClaim(1, validConfiguration());
    when(store.complete(eq(job.id()), eq(job.leaseToken()), anyString(), eq(NOW)))
        .thenAnswer(
            invocation -> {
              assertEquals(1, active());
              assertEquals(0, counter("succeeded"));
              return true;
            });

    processor.handle(task(job.id()), channel);

    assertEquals(1, counter("succeeded"));
    assertEquals(1, timerCount("succeeded"));
    assertEquals(0, counter("retried"));
    assertEquals(0, active());
    verify(channel).basicAck(42, false);
  }

  @Test
  void staleResultHasItsOwnTimerWithoutCountingSuccess() throws Exception {
    JobRecord job = prepareClaim(1, validConfiguration());
    when(store.complete(eq(job.id()), eq(job.leaseToken()), anyString(), eq(NOW)))
        .thenReturn(false);

    processor.handle(task(job.id()), channel);

    assertEquals(0, counter("succeeded"));
    assertEquals(1, timerCount("stale"));
    assertEquals(0, active());
    verify(channel).basicAck(42, false);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void durableFailureSeparatesRetriedAttemptsFromTerminalFailure(int attempt) throws Exception {
    JobRecord job = prepareClaim(attempt, "{}");
    when(store.retryOrFail(
            job.id(), job.leaseToken(), "BACKTEST_FAILED", NOW, Duration.ofSeconds(1)))
        .thenReturn(true);

    processor.handle(task(job.id()), channel);

    assertEquals(attempt < 3 ? 1 : 0, counter("retried"));
    assertEquals(attempt == 3 ? 1 : 0, counter("failed"));
    assertEquals(1, timerCount(attempt == 3 ? "failed" : "retry"));
    assertEquals(0, counter("succeeded"));
    assertEquals(0, active());
    verify(channel).basicAck(42, false);
  }

  @Test
  void uncommittedRetryDoesNotIncrementBusinessCountersOrLeakActiveTasks() throws Exception {
    JobRecord job = prepareClaim(1, "{}");
    when(store.retryOrFail(
            job.id(), job.leaseToken(), "BACKTEST_FAILED", NOW, Duration.ofSeconds(1)))
        .thenThrow(new IllegalStateException("fixture persistence failure"));

    processor.handle(task(job.id()), channel);

    assertEquals(0, counter("retried"));
    assertEquals(0, counter("failed"));
    assertEquals(0, timerCount("retry"));
    assertEquals(0, active());
    verify(channel).basicNack(42, false, true);
  }

  @Test
  void staleFailureTransitionCountsStaleAndPreservesDeliveryForRequeue() throws Exception {
    JobRecord job = prepareClaim(1, "{}");
    when(store.retryOrFail(
            job.id(), job.leaseToken(), "BACKTEST_FAILED", NOW, Duration.ofSeconds(1)))
        .thenReturn(false);

    processor.handle(task(job.id()), channel);

    assertEquals(0, counter("retried"));
    assertEquals(0, counter("failed"));
    assertEquals(1, timerCount("stale"));
    assertEquals(0, active());
    verify(channel).basicNack(42, false, true);
  }

  @Test
  void failedResultWriteDoesNotCountSuccessOrLeakActiveTasks() throws Exception {
    JobRecord job = prepareClaim(1, validConfiguration());
    when(store.complete(eq(job.id()), eq(job.leaseToken()), anyString(), eq(NOW)))
        .thenThrow(new IllegalStateException("fixture persistence failure"));

    processor.handle(task(job.id()), channel);

    assertEquals(0, counter("succeeded"));
    assertEquals(0, timerCount("succeeded"));
    assertEquals(0, active());
    verify(channel).basicNack(42, false, true);
  }

  @Test
  void acknowledgmentFailureDoesNotEraseDurableSuccessOrLeakActiveTasks() throws Exception {
    JobRecord job = prepareClaim(1, validConfiguration());
    when(store.complete(eq(job.id()), eq(job.leaseToken()), anyString(), eq(NOW))).thenReturn(true);
    doThrow(new IOException("fixture channel failure")).when(channel).basicAck(42, false);

    assertThrows(IOException.class, () -> processor.handle(task(job.id()), channel));

    assertEquals(1, counter("succeeded"));
    assertEquals(1, timerCount("succeeded"));
    assertEquals(0, active());
  }

  @Test
  void metersUseOnlyTheFixedOutcomeLabelAndNeverTaskIdentifiers() throws Exception {
    JobRecord job = prepareClaim(1, validConfiguration());
    when(store.complete(eq(job.id()), eq(job.leaseToken()), anyString(), eq(NOW))).thenReturn(true);
    processor.handle(task(job.id()), channel);
    when(store.claimJob(job.id(), NOW, LEASE)).thenReturn(Optional.empty());
    when(store.findJob(job.id())).thenReturn(Optional.of(job(job.id(), JobStatus.SUCCEEDED, NOW)));
    processor.handle(task(job.id()), channel);

    Set<String> outcomes = Set.of("succeeded", "retry", "failed", "stale");
    registry
        .getMeters()
        .forEach(
            meter ->
                meter
                    .getId()
                    .getTags()
                    .forEach(
                        tag -> {
                          assertEquals("outcome", tag.getKey());
                          assertTrue(outcomes.contains(tag.getValue()));
                        }));
  }

  private JobRecord prepareClaim(int attempts, String configuration) {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID datasetId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    JobRecord claimed =
        new JobRecord(
            id,
            datasetId,
            JobStatus.RUNNING,
            configuration,
            null,
            null,
            NOW,
            NOW,
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
            NOW.plus(LEASE),
            attempts);
    when(store.claimJob(id, NOW, LEASE)).thenReturn(Optional.of(claimed));
    when(store.findDataset(datasetId))
        .thenReturn(
            Optional.of(
                new DatasetRecord(
                    datasetId,
                    "TEST",
                    "Date,Open,High,Low,Close,Volume\n2024-01-02,100,100,100,100,1\n",
                    "a".repeat(64),
                    1,
                    LocalDate.of(2024, 1, 2),
                    LocalDate.of(2024, 1, 2),
                    NOW)));
    return claimed;
  }

  private static String validConfiguration() {
    return "{\"shortWindow\":1,\"longWindow\":2,\"initialCapital\":100,\"transactionFee\":0}";
  }

  private double counter(String name) {
    return registry.get("skulker.tasks." + name).counter().count();
  }

  private double active() {
    return registry.get("skulker.tasks.active").gauge().value();
  }

  private long timerCount(String outcome) {
    return registry.get("skulker.task.duration").tag("outcome", outcome).timer().count();
  }

  private static JobRecord job(UUID id, JobStatus status, Instant createdAt) {
    boolean running = status == JobStatus.RUNNING;
    return new JobRecord(
        id,
        UUID.fromString("00000000-0000-0000-0000-000000000002"),
        status,
        "{}",
        status == JobStatus.SUCCEEDED ? "{}" : null,
        status == JobStatus.FAILED ? "BACKTEST_FAILED" : null,
        createdAt,
        createdAt,
        running ? UUID.fromString("00000000-0000-0000-0000-000000000003") : null,
        running ? NOW.plus(LEASE) : null,
        status == JobStatus.PENDING || status == JobStatus.QUEUED ? 0 : 1);
  }

  private static Message task(UUID id) {
    return message(
        ("{\"jobId\":\"" + id + "\",\"schemaVersion\":1}").getBytes(StandardCharsets.UTF_8));
  }

  private static Message message(byte[] body) {
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(42);
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    return new Message(body, properties);
  }
}
