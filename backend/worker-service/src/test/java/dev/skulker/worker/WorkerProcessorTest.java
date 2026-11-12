package dev.skulker.worker;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import dev.skulker.store.JobRecord;
import dev.skulker.store.JobStatus;
import dev.skulker.store.JobStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
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
  private WorkerProcessor processor;

  @BeforeEach
  void createProcessor() {
    processor =
        new WorkerProcessor(
            store,
            JsonMapper.builder().findAndAddModules().build(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            LEASE,
            Duration.ofSeconds(1));
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
  }

  @Test
  void oversizedMessageIsRejectedBeforeParsingOrAccessingTheDatabase() throws Exception {
    processor.handle(message(new byte[1025]), channel);

    verify(channel).basicReject(42, false);
    verifyNoInteractions(store);
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
