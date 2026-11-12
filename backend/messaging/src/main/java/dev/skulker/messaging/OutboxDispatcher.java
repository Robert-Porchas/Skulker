package dev.skulker.messaging;

import dev.skulker.domain.BacktestTaskMessage;
import dev.skulker.domain.RabbitNames;
import dev.skulker.store.JobStore;
import dev.skulker.store.OutboxKind;
import dev.skulker.store.OutboxRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Publishes durable outbox entries only after broker confirmation and successful routing. */
@Service
@ConditionalOnProperty(name = "skulker.messaging.enabled", havingValue = "true")
public class OutboxDispatcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxDispatcher.class);
  private static final Duration LEASE = Duration.ofSeconds(30);
  private final JobStore store;
  private final JsonMapper mapper;
  private final RabbitTemplate rabbit;
  private final Clock clock;
  private final MeterRegistry registry;
  private final Counter published;
  private final Counter retried;

  public OutboxDispatcher(
      JobStore store,
      JsonMapper mapper,
      RabbitTemplate rabbit,
      Clock clock,
      MeterRegistry registry) {
    this.store = store;
    this.mapper = mapper;
    this.rabbit = rabbit;
    this.clock = clock;
    this.registry = registry;
    published = registry.counter("skulker.outbox.published");
    retried = registry.counter("skulker.outbox.retried");
    for (String outcome : new String[] {"confirmed", "retry", "stale"}) {
      registry.timer("skulker.outbox.publish.duration", "outcome", outcome);
    }
  }

  /** Returns false when no entry is due; true means one entry was attempted. */
  public boolean dispatchOne() {
    Optional<OutboxRecord> claim = store.claimOutbox(clock.instant(), LEASE);
    if (claim.isEmpty()) {
      return false;
    }
    OutboxRecord outbox = claim.orElseThrow();
    Timer.Sample sample = Timer.start(registry);
    try {
      MessageProperties properties = new MessageProperties();
      properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
      properties.setContentEncoding("UTF-8");
      properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
      properties.setMessageId(outbox.id().toString());
      properties.setCorrelationId(outbox.jobId().toString());
      if (outbox.kind() == OutboxKind.DEAD_LETTER) {
        properties.setHeader("failureCode", outbox.failureCode());
      }
      Message message =
          new Message(mapper.writeValueAsBytes(BacktestTaskMessage.of(outbox.jobId())), properties);
      CorrelationData correlation = new CorrelationData(outbox.id() + ":" + outbox.leaseToken());
      boolean deadLetter = outbox.kind() == OutboxKind.DEAD_LETTER;
      rabbit.send(
          deadLetter ? RabbitNames.DEAD_EXCHANGE : RabbitNames.TASK_EXCHANGE,
          deadLetter ? RabbitNames.DEAD_ROUTING_KEY : RabbitNames.TASK_ROUTING_KEY,
          message,
          correlation);
      CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
      // Spring guarantees returns are populated before the correlated confirmation completes.
      if (!confirm.ack() || correlation.getReturned() != null) {
        throw new AmqpException("Publication was not confirmed and routed");
      }
      boolean committed = store.markPublished(outbox.id(), outbox.leaseToken(), clock.instant());
      if (committed) {
        published.increment();
      }
      sample.stop(
          registry.timer(
              "skulker.outbox.publish.duration", "outcome", committed ? "confirmed" : "stale"));
      LOGGER
          .atInfo()
          .addKeyValue("jobId", outbox.jobId())
          .addKeyValue("outboxId", outbox.id())
          .addKeyValue("committed", committed)
          .log("Outbox publication confirmed");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      retry(outbox, exception, sample);
    } catch (ExecutionException | TimeoutException | RuntimeException exception) {
      retry(outbox, exception, sample);
    }
    return true;
  }

  private void retry(OutboxRecord outbox, Exception failure, Timer.Sample sample) {
    LOGGER
        .atWarn()
        .addKeyValue("jobId", outbox.jobId())
        .addKeyValue("outboxId", outbox.id())
        .addKeyValue("errorType", failure.getClass().getSimpleName())
        .log("Outbox publication will retry");
    long seconds = Math.min(60, 1L << Math.min(6, Math.max(0, outbox.attempts() - 1)));
    try {
      boolean committed =
          store.retryPublication(
              outbox.id(), outbox.leaseToken(), clock.instant(), Duration.ofSeconds(seconds));
      if (committed) {
        retried.increment();
      }
      sample.stop(
          registry.timer(
              "skulker.outbox.publish.duration", "outcome", committed ? "retry" : "stale"));
    } catch (RuntimeException persistenceFailure) {
      // The outbox lease can be reclaimed after expiry if this write is unavailable.
      LOGGER
          .atError()
          .addKeyValue("outboxId", outbox.id())
          .addKeyValue("errorType", persistenceFailure.getClass().getSimpleName())
          .log("Publication retry could not be persisted");
    }
  }
}
