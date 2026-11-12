package dev.skulker.api;

import dev.skulker.store.JobStatus;
import dev.skulker.store.JobStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded-cardinality snapshots of database state; scrape requests never query the database. */
@Component
public class DurableJobMetrics {
  private static final Logger LOGGER = LoggerFactory.getLogger(DurableJobMetrics.class);
  private final JobStore store;
  private final AtomicReference<Snapshot> snapshot =
      new AtomicReference<>(new Snapshot(Map.of(), false));

  public DurableJobMetrics(JobStore store, MeterRegistry registry) {
    this.store = store;
    for (JobStatus status : JobStatus.values()) {
      Gauge.builder(
              "skulker.jobs", snapshot, state -> state.get().counts().getOrDefault(status, 0L))
          .description(
              "Number of durable jobs in this state at the most recent successful store refresh")
          .tag("status", status.name())
          .register(registry);
    }
    Gauge.builder("skulker.store.metrics.up", snapshot, state -> state.get().up() ? 1 : 0)
        .description("Whether the most recent durable job metrics refresh succeeded")
        .register(registry);
  }

  @Scheduled(fixedDelayString = "${skulker.metrics.store-refresh-delay:5000}")
  public void refresh() {
    try {
      Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
      Map<JobStatus, Long> durableCounts = store.statusCounts();
      for (JobStatus status : JobStatus.values()) {
        long count = durableCounts.getOrDefault(status, 0L);
        if (count < 0) {
          throw new IllegalStateException("Durable job count must not be negative");
        }
        counts.put(status, count);
      }
      snapshot.set(new Snapshot(Map.copyOf(counts), true));
    } catch (RuntimeException exception) {
      snapshot.updateAndGet(previous -> new Snapshot(previous.counts(), false));
      LOGGER
          .atWarn()
          .addKeyValue("errorType", exception.getClass().getSimpleName())
          .log("Durable job metrics refresh failed; retaining previous counts");
    }
  }

  private record Snapshot(Map<JobStatus, Long> counts, boolean up) {}
}
