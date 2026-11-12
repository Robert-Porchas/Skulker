package dev.skulker.api;

import dev.skulker.engine.backtest.StrategyConfiguration;
import dev.skulker.store.BatchRecord;
import dev.skulker.store.JobSpecification;
import dev.skulker.store.JobStatus;
import dev.skulker.store.JobStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class BatchService {
  private final JobStore store;
  private final DatasetService datasets;
  private final JsonMapper mapper;
  private final Clock clock;

  public BatchService(JobStore store, DatasetService datasets, JsonMapper mapper, Clock clock) {
    this.store = store;
    this.datasets = datasets;
    this.mapper = mapper;
    this.clock = clock;
  }

  public BatchResponse submit(SubmitBatchRequest request) {
    requireDistinct(request.datasetIds());
    requireDistinct(request.shortWindows());
    requireDistinct(request.longWindows());
    List<String> configurations = new ArrayList<>();
    for (int shortWindow : request.shortWindows()) {
      for (int longWindow : request.longWindows()) {
        if (shortWindow < longWindow) {
          configurations.add(
              mapper.writeValueAsString(
                  new StrategyConfiguration(
                      shortWindow,
                      longWindow,
                      request.initialCapital(),
                      request.transactionFee())));
        }
      }
    }
    if (configurations.isEmpty() || configurations.size() * request.datasetIds().size() > 100) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "A batch requires between 1 and 100 valid dataset/window combinations.");
    }
    for (UUID datasetId : request.datasetIds()) {
      datasets.requireDataset(datasetId);
    }
    List<JobSpecification> tasks = new ArrayList<>();
    for (UUID datasetId : request.datasetIds()) {
      for (String configuration : configurations) {
        tasks.add(new JobSpecification(datasetId, configuration));
      }
    }
    return response(store.createBatch(tasks, clock.instant()));
  }

  public BatchResponse get(UUID id) {
    return response(
        store
            .findBatch(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Batch was not found.")));
  }

  private BatchResponse response(BatchRecord batch) {
    Map<UUID, JobStatus> states = store.batchStatuses(batch.id());
    if (states.size() != batch.jobIds().size()) {
      throw new IllegalStateException("Batch membership is inconsistent");
    }
    int succeeded = (int) states.values().stream().filter(s -> s == JobStatus.SUCCEEDED).count();
    int failed = (int) states.values().stream().filter(s -> s == JobStatus.FAILED).count();
    int cancelled = (int) states.values().stream().filter(s -> s == JobStatus.CANCELLED).count();
    JobStatus status;
    if (succeeded == states.size()) {
      status = JobStatus.SUCCEEDED;
    } else if (succeeded + failed + cancelled == states.size()) {
      status = failed > 0 ? JobStatus.FAILED : JobStatus.CANCELLED;
    } else if (states.values().stream().allMatch(s -> s == JobStatus.PENDING)) {
      status = JobStatus.PENDING;
    } else if (states.values().stream()
        .allMatch(s -> s == JobStatus.PENDING || s == JobStatus.QUEUED)) {
      status = JobStatus.QUEUED;
    } else {
      status = JobStatus.RUNNING;
    }
    return new BatchResponse(
        batch.id(), status, batch.createdAt(), batch.jobIds(), states.size(), succeeded, failed);
  }

  private static void requireDistinct(List<?> values) {
    if (new HashSet<>(values).size() != values.size()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "Batch inputs must not contain duplicates.");
    }
  }
}
