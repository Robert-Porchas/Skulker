package dev.skulker.api;

import dev.skulker.engine.HistoricalPriceSeries;
import dev.skulker.engine.backtest.BacktestResult;
import dev.skulker.engine.backtest.MovingAverageBacktester;
import dev.skulker.engine.backtest.StrategyConfiguration;
import dev.skulker.engine.csv.CsvPriceParser;
import dev.skulker.store.DatasetRecord;
import dev.skulker.store.JobRecord;
import dev.skulker.store.JobStore;
import java.io.IOException;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Local M2 execution adapter. The database owns claims and fences stale completion attempts. */
@Service
public class JobRunner {
  private static final Logger LOGGER = LoggerFactory.getLogger(JobRunner.class);
  private static final Duration LEASE = Duration.ofMinutes(5);
  private final JobStore store;
  private final JsonMapper mapper;
  private final Clock clock;
  private final CsvPriceParser parser = new CsvPriceParser();
  private final MovingAverageBacktester backtester = new MovingAverageBacktester();

  public JobRunner(JobStore store, JsonMapper mapper, Clock clock) {
    this.store = store;
    this.mapper = mapper;
    this.clock = clock;
  }

  /** Claims and attempts one durable job; false means no claim was available. */
  public boolean runOne() {
    Optional<JobRecord> claim = store.claimNext(clock.instant(), LEASE);
    if (claim.isEmpty()) {
      return false;
    }
    JobRecord job = claim.orElseThrow();
    try {
      DatasetRecord dataset =
          store
              .findDataset(job.datasetId())
              .orElseThrow(() -> new IllegalStateException("Claimed dataset is unavailable"));
      StrategyConfiguration configuration =
          mapper.readValue(job.configurationJson(), StrategyConfiguration.class);
      HistoricalPriceSeries series =
          parser.parse(dataset.symbol(), new StringReader(dataset.csv()));
      BacktestResult result = backtester.run(series, configuration);
      boolean committed =
          store.complete(
              job.id(), job.leaseToken(), mapper.writeValueAsString(result), clock.instant());
      LOGGER
          .atInfo()
          .addKeyValue("jobId", job.id())
          .addKeyValue("committed", committed)
          .log("Backtest execution finished");
    } catch (IOException | RuntimeException exception) {
      LOGGER
          .atError()
          .addKeyValue("jobId", job.id())
          .addKeyValue("errorType", exception.getClass().getSimpleName())
          .log("Backtest execution failed");
      try {
        store.fail(job.id(), job.leaseToken(), "BACKTEST_FAILED", clock.instant());
      } catch (RuntimeException persistenceFailure) {
        // The durable RUNNING lease remains recoverable if failure persistence is unavailable.
        LOGGER
            .atError()
            .addKeyValue("jobId", job.id())
            .addKeyValue("errorType", persistenceFailure.getClass().getSimpleName())
            .log("Backtest failure could not be persisted");
      }
    }
    return true;
  }
}
