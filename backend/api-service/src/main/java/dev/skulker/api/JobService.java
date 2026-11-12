package dev.skulker.api;

import dev.skulker.engine.backtest.BacktestResult;
import dev.skulker.engine.backtest.StrategyConfiguration;
import dev.skulker.store.JobRecord;
import dev.skulker.store.JobStatus;
import dev.skulker.store.JobStore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JobService {
  private final JobStore store;
  private final DatasetService datasets;
  private final JsonMapper mapper;
  private final Clock clock;

  public JobService(JobStore store, DatasetService datasets, JsonMapper mapper, Clock clock) {
    this.store = store;
    this.datasets = datasets;
    this.mapper = mapper;
    this.clock = clock;
  }

  public JobResponse submit(SubmitJobRequest request) {
    StrategyConfiguration configuration;
    try {
      configuration =
          new StrategyConfiguration(
              request.shortWindow(),
              request.longWindow(),
              request.initialCapital(),
              request.transactionFee());
    } catch (IllegalArgumentException exception) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "Strategy windows must satisfy 0 < shortWindow < longWindow.");
    }
    datasets.requireDataset(request.datasetId());
    return JobResponse.from(
        store.createJob(
            request.datasetId(), mapper.writeValueAsString(configuration), clock.instant()));
  }

  public JobRecord requireJob(UUID id) {
    return store
        .findJob(id)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Job was not found."));
  }

  public List<JobResponse> list(int limit, int offset) {
    DatasetService.validatePage(limit, offset);
    return store.listJobs(limit, offset).stream().map(JobResponse::from).toList();
  }

  public BacktestResult result(UUID id) {
    JobRecord job = requireJob(id);
    if (job.status() != JobStatus.SUCCEEDED) {
      throw new ApiException(
          HttpStatus.CONFLICT, "A result is available only after the job succeeds.");
    }
    if (job.resultJson() == null) {
      throw new IllegalStateException("Successful job has no stored result");
    }
    return mapper.readValue(job.resultJson(), BacktestResult.class);
  }
}
