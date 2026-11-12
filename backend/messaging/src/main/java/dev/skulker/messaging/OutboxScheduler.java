package dev.skulker.messaging;

import dev.skulker.store.JobStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded polling keeps durable recovery and publication independent of HTTP requests. */
public class OutboxScheduler {
  private static final Logger LOGGER = LoggerFactory.getLogger(OutboxScheduler.class);
  private final JobStore store;
  private final OutboxDispatcher dispatcher;
  private final Clock clock;

  public OutboxScheduler(JobStore store, OutboxDispatcher dispatcher, Clock clock) {
    this.store = store;
    this.dispatcher = dispatcher;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${skulker.messaging.poll-delay:1000}")
  public void poll() {
    try {
      store.recoverExpiredJobs(clock.instant(), 100);
      for (int index = 0; index < 20 && !Thread.currentThread().isInterrupted(); index++) {
        if (!dispatcher.dispatchOne()) {
          break;
        }
      }
    } catch (RuntimeException failure) {
      LOGGER
          .atError()
          .addKeyValue("errorType", failure.getClass().getSimpleName())
          .log("Outbox polling failed; durable work remains recoverable");
    }
  }
}
