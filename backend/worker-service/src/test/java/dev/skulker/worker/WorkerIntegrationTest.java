package dev.skulker.worker;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import dev.skulker.domain.BacktestTaskMessage;
import dev.skulker.domain.RabbitNames;
import dev.skulker.engine.backtest.MovingAverageBacktester;
import dev.skulker.engine.backtest.StrategyConfiguration;
import dev.skulker.engine.csv.CsvPriceParser;
import dev.skulker.messaging.OutboxDispatcher;
import dev.skulker.store.DatasetRecord;
import dev.skulker.store.JobRecord;
import dev.skulker.store.JobStatus;
import dev.skulker.store.JobStore;
import dev.skulker.store.OutboxRecord;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "skulker.messaging.enabled=true",
      "skulker.messaging.scheduling-enabled=false",
      "spring.rabbitmq.listener.simple.auto-startup=false",
      "spring.rabbitmq.listener.direct.auto-startup=false",
      "spring.rabbitmq.publisher-confirm-type=correlated",
      "spring.rabbitmq.publisher-returns=true",
      "spring.rabbitmq.template.mandatory=true",
      "skulker.worker.lease-duration=PT5M",
      "skulker.worker.retry-backoff=PT1S"
    })
@Import(WorkerIntegrationTest.ClockConfiguration.class)
class WorkerIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Duration LEASE = Duration.ofMinutes(5);
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

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

  @Container
  static final RabbitMQContainer RABBIT =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.7-management-alpine"));

  @DynamicPropertySource
  static void serviceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.rabbitmq.host", RABBIT::getHost);
    registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
  }

  @Autowired private JobStore store;
  @Autowired private WorkerProcessor processor;
  @Autowired private OutboxDispatcher dispatcher;
  @Autowired private JsonMapper json;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RabbitAdmin admin;
  @Autowired private RabbitListenerEndpointRegistry listeners;
  @Autowired private MutableClock clock;

  @BeforeEach
  void resetDurableStateAndQueues() throws Exception {
    listeners.getListenerContainers().forEach(container -> container.stop());
    clock.set(NOW);
    jdbc.update("DELETE FROM jobs");
    jdbc.update("DELETE FROM batches");
    jdbc.update("DELETE FROM datasets");
    admin.initialize();
    try (Connection connection = connection();
        Channel channel = connection.createChannel()) {
      channel.queuePurge(RabbitNames.TASK_QUEUE);
      channel.queuePurge(RabbitNames.DEAD_QUEUE);
    }
  }

  @Test
  void registeredRabbitListenerConsumesPublishedTaskAndPersistsBacktestResult() {
    var listener = Objects.requireNonNull(listeners.getListenerContainer("backtestWorker"));
    try {
      listener.start();
      JobRecord job = createJob(CONFIGURATION);
      assertTrue(dispatcher.dispatchOne());

      await()
          .atMost(Duration.ofSeconds(15))
          .untilAsserted(
              () ->
                  assertEquals(
                      JobStatus.SUCCEEDED, store.findJob(job.id()).orElseThrow().status()));

      JobRecord completed = store.findJob(job.id()).orElseThrow();
      assertEquals(1, completed.attempts());
      assertEquals(
          new BigDecimal("27"),
          json.readTree(completed.resultJson()).get("finalPortfolioValue").decimalValue());
    } finally {
      listener.stop();
    }
  }

  @Test
  void committedOutboxPublishesPersistentTaskAndAcknowledgesOnlyAfterResultCommit()
      throws Exception {
    JobRecord job = createJob(CONFIGURATION);
    assertEquals(JobStatus.PENDING, store.findJob(job.id()).orElseThrow().status());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE job_id = ?", Integer.class, job.id()));

    assertTrue(dispatcher.dispatchOne());
    assertEquals(JobStatus.QUEUED, store.findJob(job.id()).orElseThrow().status());

    try (Connection connection = connection();
        Channel channel = connection.createChannel()) {
      GetResponse delivery = nextDelivery(channel, RabbitNames.TASK_QUEUE);
      assertEquals(2, delivery.getProps().getDeliveryMode());
      assertEquals(job.id(), json.readValue(delivery.getBody(), BacktestTaskMessage.class).jobId());
      processor.handle(message(delivery), channel);

      JobRecord completed = store.findJob(job.id()).orElseThrow();
      assertEquals(JobStatus.SUCCEEDED, completed.status());
      assertEquals(
          new BigDecimal("27"),
          json.readTree(completed.resultJson()).get("finalPortfolioValue").decimalValue());
      assertEquals(1, completed.attempts());
      assertAcknowledged(channel);
    }
  }

  @Test
  void duplicateTaskDeliveryDoesNotComputeOrCommitASecondResult() throws Exception {
    JobRecord job = createJob(CONFIGURATION);
    assertTrue(dispatcher.dispatchOne());
    try (Connection connection = connection();
        Channel channel = connection.createChannel()) {
      GetResponse first = nextDelivery(channel, RabbitNames.TASK_QUEUE);
      processor.handle(message(first), channel);
      String committed = store.findJob(job.id()).orElseThrow().resultJson();
      publish(channel, first.getBody());

      processor.handle(message(nextDelivery(channel, RabbitNames.TASK_QUEUE)), channel);

      JobRecord afterDuplicate = store.findJob(job.id()).orElseThrow();
      assertEquals(JobStatus.SUCCEEDED, afterDuplicate.status());
      assertEquals(1, afterDuplicate.attempts());
      assertEquals(committed, afterDuplicate.resultJson());
      assertAcknowledged(channel);
    }
  }

  @Test
  void publisherCrashAfterBrokerAcceptanceBeforeOutboxCommitCausesOnlySafeDuplicateExecution()
      throws Exception {
    JobRecord job = createJob(CONFIGURATION);
    Duration publicationLease = Duration.ofSeconds(30);
    OutboxRecord interruptedPublisher =
        store.claimOutbox(clock.instant(), publicationLease).orElseThrow();
    try (Connection connection = connection();
        Channel channel = connection.createChannel()) {
      publish(channel, json.writeValueAsBytes(BacktestTaskMessage.of(job.id())));
      // The broker confirmed, but the publisher died before recording markPublished.
      clock.advance(publicationLease);
      assertTrue(dispatcher.dispatchOne());
      assertFalse(
          store.markPublished(
              interruptedPublisher.id(), interruptedPublisher.leaseToken(), clock.instant()));

      processor.handle(message(nextDelivery(channel, RabbitNames.TASK_QUEUE)), channel);
      processor.handle(message(nextDelivery(channel, RabbitNames.TASK_QUEUE)), channel);

      JobRecord completed = store.findJob(job.id()).orElseThrow();
      assertEquals(JobStatus.SUCCEEDED, completed.status());
      assertEquals(1, completed.attempts());
      assertAcknowledged(channel);
    }
  }

  @Test
  void crashAfterResultCommitBeforeAcknowledgmentRedeliversWithoutOverwritingResult()
      throws Exception {
    JobRecord job = createJob(CONFIGURATION);
    assertTrue(dispatcher.dispatchOne());
    String result = completedResult();
    try (Connection connection = connection();
        Channel channel = connection.createChannel()) {
      nextDelivery(channel, RabbitNames.TASK_QUEUE);
      JobRecord owner = store.claimJob(job.id(), clock.instant(), LEASE).orElseThrow();
      assertTrue(store.complete(job.id(), owner.leaseToken(), result, clock.instant()));
      // Closing this real channel with no ACK simulates the process dying after commit.
    }

    try (Connection connection = connection();
        Channel channel = connection.createChannel()) {
      GetResponse redelivery = nextDelivery(channel, RabbitNames.TASK_QUEUE);
      assertTrue(redelivery.getEnvelope().isRedeliver());
      processor.handle(message(redelivery), channel);

      JobRecord recovered = store.findJob(job.id()).orElseThrow();
      assertEquals(1, recovered.attempts());
      assertEquals(JobStatus.SUCCEEDED, recovered.status());
      assertEquals(json.readTree(result), json.readTree(recovered.resultJson()));
      assertAcknowledged(channel);
    }
  }

  @Test
  void expiredWorkerIsFencedAndRecoveryCreatesADurableReplacementTask() throws Exception {
    JobRecord job = createJob(CONFIGURATION);
    assertTrue(dispatcher.dispatchOne());
    JobRecord stale;
    try (Connection connection = connection();
        Channel channel = connection.createChannel()) {
      nextDelivery(channel, RabbitNames.TASK_QUEUE);
      stale = store.claimJob(job.id(), clock.instant(), LEASE).orElseThrow();
    }
    clock.advance(LEASE);
    assertEquals(1, store.recoverExpiredJobs(clock.instant(), 100));
    assertFalse(store.complete(job.id(), stale.leaseToken(), "{}", clock.instant()));
    assertTrue(dispatcher.dispatchOne());

    try (Connection connection = connection();
        Channel channel = connection.createChannel()) {
      processor.handle(message(nextDelivery(channel, RabbitNames.TASK_QUEUE)), channel);
      processor.handle(message(nextDelivery(channel, RabbitNames.TASK_QUEUE)), channel);

      JobRecord recovered = store.findJob(job.id()).orElseThrow();
      assertEquals(JobStatus.SUCCEEDED, recovered.status());
      assertEquals(2, recovered.attempts());
      assertNull(recovered.leaseToken());
      assertAcknowledged(channel);
    }
  }

  @Test
  void failedCalculationsRetryThreeTimesThenPublishDurableDeadLetter() throws Exception {
    JobRecord job = createJob("{}");
    try (Connection connection = connection();
        Channel channel = connection.createChannel()) {
      for (int attempt = 1; attempt <= 3; attempt++) {
        assertTrue(dispatcher.dispatchOne());
        processor.handle(message(nextDelivery(channel, RabbitNames.TASK_QUEUE)), channel);
        JobRecord afterAttempt = store.findJob(job.id()).orElseThrow();
        assertEquals(attempt, afterAttempt.attempts());
        assertEquals(attempt == 3 ? JobStatus.FAILED : JobStatus.PENDING, afterAttempt.status());
        assertNull(afterAttempt.resultJson());
        if (attempt < 3) {
          assertFalse(dispatcher.dispatchOne());
          clock.advance(Duration.ofSeconds(2));
        }
      }
      assertTrue(dispatcher.dispatchOne());
      GetResponse deadLetter = nextDelivery(channel, RabbitNames.DEAD_QUEUE);
      assertEquals(
          job.id(), json.readValue(deadLetter.getBody(), BacktestTaskMessage.class).jobId());
      channel.basicAck(deadLetter.getEnvelope().getDeliveryTag(), false);
      assertFalse(dispatcher.dispatchOne());
      assertAcknowledged(channel);
    }
  }

  @Test
  void poisonPayloadUsesBrokerDeadLetterRouteWithoutCreatingAJob() throws Exception {
    byte[] poison = "not-json".getBytes(StandardCharsets.UTF_8);
    try (Connection connection = connection();
        Channel channel = connection.createChannel()) {
      publish(channel, poison);
      processor.handle(message(nextDelivery(channel, RabbitNames.TASK_QUEUE)), channel);

      GetResponse deadLetter = nextDelivery(channel, RabbitNames.DEAD_QUEUE);
      assertArrayEquals(poison, deadLetter.getBody());
      assertTrue(deadLetter.getProps().getHeaders().containsKey("x-death"));
      channel.basicAck(deadLetter.getEnvelope().getDeliveryTag(), false);
      assertTrue(store.listJobs(100, 0).isEmpty());
      assertAcknowledged(channel);
    }
  }

  @Test
  void unroutablePublicationRemainsDurableAndRetriesAfterBindingIsRestored() throws Exception {
    JobRecord job = createJob(CONFIGURATION);
    try (Connection connection = connection();
        Channel channel = connection.createChannel()) {
      channel.queueUnbind(
          RabbitNames.TASK_QUEUE, RabbitNames.TASK_EXCHANGE, RabbitNames.TASK_ROUTING_KEY);
      try {
        dispatcher.dispatchOne();
        assertEquals(JobStatus.PENDING, store.findJob(job.id()).orElseThrow().status());
      } finally {
        channel.queueBind(
            RabbitNames.TASK_QUEUE, RabbitNames.TASK_EXCHANGE, RabbitNames.TASK_ROUTING_KEY);
      }
      clock.advance(Duration.ofSeconds(2));
      assertTrue(dispatcher.dispatchOne());
      assertEquals(JobStatus.QUEUED, store.findJob(job.id()).orElseThrow().status());
      GetResponse delivery = nextDelivery(channel, RabbitNames.TASK_QUEUE);
      assertEquals(job.id(), json.readValue(delivery.getBody(), BacktestTaskMessage.class).jobId());
      processor.handle(message(delivery), channel);
      assertEquals(JobStatus.SUCCEEDED, store.findJob(job.id()).orElseThrow().status());
      assertAcknowledged(channel);
    }
  }

  private JobRecord createJob(String configuration) {
    DatasetRecord dataset =
        store.saveDataset(
            "TEST",
            CSV,
            "a".repeat(64),
            6,
            LocalDate.of(2024, 1, 2),
            LocalDate.of(2024, 1, 9),
            clock.instant());
    return store.createJob(dataset.id(), configuration, clock.instant());
  }

  private String completedResult() throws Exception {
    var prices = new CsvPriceParser().parse("TEST", new StringReader(CSV));
    var configuration = new StrategyConfiguration(1, 2, new BigDecimal("100"), BigDecimal.ONE);
    return json.writeValueAsString(new MovingAverageBacktester().run(prices, configuration));
  }

  private static Connection connection() throws Exception {
    com.rabbitmq.client.ConnectionFactory factory = new com.rabbitmq.client.ConnectionFactory();
    factory.setHost(RABBIT.getHost());
    factory.setPort(RABBIT.getAmqpPort());
    factory.setUsername(RABBIT.getAdminUsername());
    factory.setPassword(RABBIT.getAdminPassword());
    return factory.newConnection("skulker-worker-integration");
  }

  private static GetResponse nextDelivery(Channel channel, String queue) {
    return await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> channel.basicGet(queue, false), Objects::nonNull);
  }

  private static void publish(Channel channel, byte[] body) throws Exception {
    channel.confirmSelect();
    channel.basicPublish(
        RabbitNames.TASK_EXCHANGE,
        RabbitNames.TASK_ROUTING_KEY,
        new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build(),
        body);
    channel.waitForConfirmsOrDie(10000);
  }

  private static Message message(GetResponse delivery) {
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(delivery.getEnvelope().getDeliveryTag());
    properties.setRedelivered(delivery.getEnvelope().isRedeliver());
    properties.setReceivedExchange(delivery.getEnvelope().getExchange());
    properties.setReceivedRoutingKey(delivery.getEnvelope().getRoutingKey());
    properties.setContentType(delivery.getProps().getContentType());
    return new Message(delivery.getBody(), properties);
  }

  private static void assertAcknowledged(Channel channel) throws Exception {
    // Recover is a synchronous broker barrier: any missing ACK would requeue a delivery here.
    channel.basicRecover(true);
    assertNull(channel.basicGet(RabbitNames.TASK_QUEUE, false));
  }

  @TestConfiguration
  static class ClockConfiguration {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(new AtomicReference<>(NOW), ZoneOffset.UTC);
    }
  }

  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    MutableClock(AtomicReference<Instant> current, ZoneId zone) {
      this.current = current;
      this.zone = zone;
    }

    void set(Instant value) {
      current.set(value);
    }

    void advance(Duration duration) {
      current.updateAndGet(value -> value.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
      return new MutableClock(current, newZone);
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }
}
