package dev.skulker.worker;

import com.rabbitmq.client.Channel;
import dev.skulker.domain.BacktestTaskMessage;
import dev.skulker.domain.RabbitNames;
import dev.skulker.engine.HistoricalPriceSeries;
import dev.skulker.engine.backtest.BacktestResult;
import dev.skulker.engine.backtest.MovingAverageBacktester;
import dev.skulker.engine.backtest.StrategyConfiguration;
import dev.skulker.engine.csv.CsvPriceParser;
import dev.skulker.store.DatasetRecord;
import dev.skulker.store.JobRecord;
import dev.skulker.store.JobStatus;
import dev.skulker.store.JobStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import tools.jackson.databind.json.JsonMapper;

/** Manual acknowledgments follow durable result or retry writes; duplicate deliveries are safe. */
@Service
public class WorkerProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(WorkerProcessor.class);
  private final JobStore store;
  private final JsonMapper mapper;
  private final Clock clock;
  private final Duration leaseDuration;
  private final Duration retryBackoff;
  private final MeterRegistry registry;
  private final AtomicInteger active = new AtomicInteger();
  private final Counter succeeded;
  private final Counter retried;
  private final Counter failed;
  private final Counter duplicates;
  private final Counter rejected;
  private final CsvPriceParser parser = new CsvPriceParser();
  private final MovingAverageBacktester backtester = new MovingAverageBacktester();
  private final RetryTemplate databaseRetry =
      new RetryTemplate(
          RetryPolicy.builder()
              .includes(DataAccessException.class, TransactionException.class)
              .maxRetries(2)
              .delay(Duration.ofSeconds(1))
              .multiplier(2)
              .maxDelay(Duration.ofSeconds(2))
              .build());

  public WorkerProcessor(
      JobStore store,
      JsonMapper mapper,
      Clock clock,
      @Value("${skulker.worker.lease-duration:PT5M}") Duration leaseDuration,
      @Value("${skulker.worker.retry-backoff:PT1S}") Duration retryBackoff,
      MeterRegistry registry) {
    this.store = store;
    this.mapper = mapper;
    this.clock = clock;
    this.leaseDuration = Objects.requireNonNull(leaseDuration);
    this.retryBackoff = Objects.requireNonNull(retryBackoff);
    this.registry = Objects.requireNonNull(registry);
    if (leaseDuration.isNegative()
        || leaseDuration.isZero()
        || leaseDuration.toNanosPart() % 1000 != 0
        || retryBackoff.isNegative()) {
      throw new IllegalArgumentException(
          "Worker lease must be positive with microsecond precision and retry backoff nonnegative");
    }
    succeeded = registry.counter("skulker.tasks.succeeded");
    retried = registry.counter("skulker.tasks.retried");
    failed = registry.counter("skulker.tasks.failed");
    duplicates = registry.counter("skulker.tasks.duplicates");
    rejected = registry.counter("skulker.tasks.rejected");
    registry.gauge("skulker.tasks.active", active);
    for (String outcome : new String[] {"succeeded", "retry", "failed", "stale"}) {
      registry.timer("skulker.task.duration", "outcome", outcome);
    }
  }

  @RabbitListener(
      id = "backtestWorker",
      queues = RabbitNames.TASK_QUEUE,
      ackMode = "MANUAL",
      concurrency = "${skulker.worker.concurrency:1}")
  public void handle(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    BacktestTaskMessage task;
    try {
      if (message.getBody().length == 0 || message.getBody().length > 1024) {
        throw new IllegalArgumentException("Invalid task size");
      }
      task = Objects.requireNonNull(mapper.readValue(message.getBody(), BacktestTaskMessage.class));
    } catch (RuntimeException malformed) {
      LOGGER.atWarn().addKeyValue("deliveryTag", tag).log("Rejected malformed backtest task");
      channel.basicReject(tag, false);
      rejected.increment();
      return;
    }
    Optional<JobRecord> claim;
    try {
      claim =
          databaseRetry.invoke(
              () -> {
                Optional<JobRecord> available =
                    store.claimJob(task.jobId(), clock.instant(), leaseDuration);
                if (available.isPresent()) {
                  return available;
                }
                JobRecord existing =
                    store.findJob(task.jobId()).orElseThrow(UnknownTaskException::new);
                if (existing.status() == JobStatus.PENDING
                    || existing.status() == JobStatus.QUEUED) {
                  // A worker clock may lag publication time, or this delivery may precede retry
                  // availability.
                  // Preserve the delivery until claimable; no RUNNING lease exists to recover these
                  // states.
                  throw new TransientDataAccessResourceException(
                      "Backtest task is not yet claimable");
                }
                return Optional.empty();
              });
    } catch (UnknownTaskException unknown) {
      LOGGER.atWarn().addKeyValue("jobId", task.jobId()).log("Rejected unknown backtest task");
      channel.basicReject(tag, false);
      rejected.increment();
      return;
    } catch (RuntimeException failure) {
      requeue(channel, tag, task.jobId().toString(), failure);
      return;
    }
    if (claim.isEmpty()) {
      // Terminal or busy duplicates are covered by durable state and expired-lease recovery.
      duplicates.increment();
      channel.basicAck(tag, false);
      return;
    }
    JobRecord job = claim.orElseThrow();
    active.incrementAndGet();
    Timer.Sample sample = Timer.start(registry);
    try {
      executeClaimed(job, channel, tag, sample);
    } finally {
      active.decrementAndGet();
    }
  }

  private void executeClaimed(JobRecord job, Channel channel, long tag, Timer.Sample sample)
      throws IOException {
    Optional<DatasetRecord> dataset;
    try {
      dataset = databaseRetry.invoke(() -> store.findDataset(job.datasetId()));
    } catch (RuntimeException failure) {
      requeue(channel, tag, job.id().toString(), failure);
      return;
    }
    String resultJson;
    try {
      DatasetRecord source =
          dataset.orElseThrow(() -> new IllegalStateException("Dataset is unavailable"));
      StrategyConfiguration configuration =
          mapper.readValue(job.configurationJson(), StrategyConfiguration.class);
      HistoricalPriceSeries series = parser.parse(source.symbol(), new StringReader(source.csv()));
      BacktestResult result = backtester.run(series, configuration);
      resultJson = mapper.writeValueAsString(result);
    } catch (IOException | RuntimeException failure) {
      retryJob(job, channel, tag, failure, sample);
      return;
    }
    boolean committed;
    try {
      committed =
          databaseRetry.invoke(
              () -> store.complete(job.id(), job.leaseToken(), resultJson, clock.instant()));
    } catch (RuntimeException failure) {
      requeue(channel, tag, job.id().toString(), failure);
      return;
    }
    LOGGER
        .atInfo()
        .addKeyValue("jobId", job.id())
        .addKeyValue("committed", committed)
        .log("Distributed backtest execution finished");
    if (committed) {
      succeeded.increment();
    }
    sample.stop(
        registry.timer("skulker.task.duration", "outcome", committed ? "succeeded" : "stale"));
    // A stale result is deliberately discarded; the current lease holder remains authoritative.
    channel.basicAck(tag, false);
  }

  private void retryJob(
      JobRecord job, Channel channel, long tag, Exception failure, Timer.Sample sample)
      throws IOException {
    LOGGER
        .atWarn()
        .addKeyValue("jobId", job.id())
        .addKeyValue("errorType", failure.getClass().getSimpleName())
        .log("Backtest execution failed; recording durable retry or terminal failure");
    boolean committed;
    try {
      committed =
          databaseRetry.invoke(
              () ->
                  store.retryOrFail(
                      job.id(),
                      job.leaseToken(),
                      "BACKTEST_FAILED",
                      clock.instant(),
                      retryBackoff));
    } catch (RuntimeException persistenceFailure) {
      requeue(channel, tag, job.id().toString(), persistenceFailure);
      return;
    }
    if (committed) {
      // JobStore's accepted execution policy permits at most three claims, including crashes.
      boolean terminal = job.attempts() >= 3;
      if (terminal) {
        failed.increment();
      } else {
        retried.increment();
      }
      sample.stop(
          registry.timer("skulker.task.duration", "outcome", terminal ? "failed" : "retry"));
      channel.basicAck(tag, false);
    } else {
      sample.stop(registry.timer("skulker.task.duration", "outcome", "stale"));
      channel.basicNack(tag, false, true);
    }
  }

  private static void requeue(Channel channel, long tag, String jobId, RuntimeException failure)
      throws IOException {
    LOGGER
        .atError()
        .addKeyValue("jobId", jobId)
        .addKeyValue("errorType", failure.getClass().getSimpleName())
        .log("Durable job operation failed; delivery requeued");
    channel.basicNack(tag, false, true);
  }

  private static final class UnknownTaskException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
