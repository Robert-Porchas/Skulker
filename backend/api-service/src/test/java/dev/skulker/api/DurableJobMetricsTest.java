package dev.skulker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.skulker.store.JobStatus;
import dev.skulker.store.JobStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class DurableJobMetricsTest {
  private final JobStore store = mock(JobStore.class);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final DurableJobMetrics metrics = new DurableJobMetrics(store, registry);

  @Test
  void registersEveryStateWithoutDatabaseAccessAndStartsUnavailable() {
    for (JobStatus status : JobStatus.values()) {
      assertEquals(0, count(status));
    }
    assertEquals(6, registry.find("skulker.jobs").gauges().size());
    assertEquals(0, up());
    verifyNoInteractions(store);
  }

  @Test
  void successfulRefreshPublishesCurrentDurableCountsAndExplicitZeros() {
    when(store.statusCounts()).thenReturn(Map.of(JobStatus.PENDING, 3L, JobStatus.SUCCEEDED, 12L));

    metrics.refresh();

    assertEquals(3, count(JobStatus.PENDING));
    assertEquals(12, count(JobStatus.SUCCEEDED));
    assertEquals(0, count(JobStatus.FAILED));
    assertEquals(1, up());
  }

  @Test
  void failedRefreshRetainsLastSuccessfulCountsAndSignalsUnavailability() {
    when(store.statusCounts())
        .thenReturn(Map.of(JobStatus.RUNNING, 4L))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));
    metrics.refresh();

    metrics.refresh();

    assertEquals(4, count(JobStatus.RUNNING));
    assertEquals(0, count(JobStatus.PENDING));
    assertEquals(0, up());
  }

  @Test
  void laterSuccessRecoversAvailabilityAndReplacesStaleCounts() {
    when(store.statusCounts())
        .thenReturn(Map.of(JobStatus.RUNNING, 4L))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"))
        .thenReturn(Map.of(JobStatus.SUCCEEDED, 4L));
    metrics.refresh();
    metrics.refresh();

    metrics.refresh();

    assertEquals(0, count(JobStatus.RUNNING));
    assertEquals(4, count(JobStatus.SUCCEEDED));
    assertEquals(1, up());
  }

  @Test
  void initialDatabaseFailureKeepsFiniteZeroCounts() {
    when(store.statusCounts())
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));

    metrics.refresh();

    for (JobStatus status : JobStatus.values()) {
      assertEquals(0, count(status));
    }
    assertEquals(0, up());
  }

  @Test
  void scrapeReadsCachedCountsAndLabelsHaveNoJobOrDatasetIdentity() {
    when(store.statusCounts()).thenReturn(Map.of(JobStatus.QUEUED, 2L));
    metrics.refresh();
    for (var meter : registry.getMeters()) {
      assertFalse(
          meter.getId().getTags().stream()
              .anyMatch(tag -> tag.getKey().equals("jobId") || tag.getKey().equals("datasetId")));
    }
    assertEquals(2, count(JobStatus.QUEUED));
    assertEquals(2, count(JobStatus.QUEUED));
    verify(store, times(1)).statusCounts();
  }

  private double count(JobStatus status) {
    var gauge = registry.find("skulker.jobs").tag("status", status.name()).gauge();
    assertNotNull(gauge);
    return gauge.value();
  }

  private double up() {
    var gauge = registry.find("skulker.store.metrics.up").gauge();
    assertNotNull(gauge);
    return gauge.value();
  }
}
