package dev.skulker.store;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL is authoritative for datasets, execution leases, terminal state, and results. Callers
 * provide time explicitly. Every mutating operation commits atomically.
 */
@Service
@Transactional(readOnly = true)
public class JobStore {
  private final DatasetRepository datasets;
  private final JobRepository jobs;

  JobStore(DatasetRepository datasets, JobRepository jobs) {
    this.datasets = datasets;
    this.jobs = jobs;
  }

  @Transactional
  public DatasetRecord saveDataset(
      String symbol,
      String csv,
      String sha256,
      int barCount,
      LocalDate startDate,
      LocalDate endDate,
      Instant now) {
    requireText(symbol, "symbol");
    if (symbol.length() > 64 || !symbol.equals(symbol.strip())) {
      throw new IllegalArgumentException(
          "symbol must contain at most 64 characters without surrounding whitespace");
    }
    requireText(csv, "csv");
    requireText(sha256, "sha256");
    if (!sha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "sha256 must contain exactly 64 lowercase hexadecimal characters");
    }
    Objects.requireNonNull(startDate, "startDate must not be null");
    Objects.requireNonNull(endDate, "endDate must not be null");
    if (barCount <= 0 || endDate.isBefore(startDate)) {
      throw new IllegalArgumentException("dataset must have positive barCount and ordered dates");
    }
    DatasetEntity dataset =
        new DatasetEntity(
            UUID.randomUUID(),
            symbol,
            csv,
            sha256,
            barCount,
            startDate,
            endDate,
            databaseTime(now));
    return datasets.saveAndFlush(dataset).snapshot();
  }

  public Optional<DatasetRecord> findDataset(UUID id) {
    return datasets
        .findById(Objects.requireNonNull(id, "id must not be null"))
        .map(DatasetEntity::snapshot);
  }

  public List<DatasetRecord> listDatasets(int limit, int offset) {
    validatePage(limit, offset);
    return datasets.list(limit, offset).stream().map(DatasetEntity::snapshot).toList();
  }

  @Transactional
  public JobRecord createJob(UUID datasetId, String configurationJson, Instant now) {
    Objects.requireNonNull(datasetId, "datasetId must not be null");
    requireText(configurationJson, "configurationJson");
    Instant timestamp = databaseTime(now);
    if (!datasets.existsById(datasetId)) {
      throw new IllegalArgumentException("dataset does not exist: " + datasetId);
    }
    return jobs.saveAndFlush(
            new JobEntity(UUID.randomUUID(), datasetId, configurationJson, timestamp))
        .snapshot();
  }

  public Optional<JobRecord> findJob(UUID id) {
    return jobs.findById(Objects.requireNonNull(id, "id must not be null"))
        .map(JobEntity::snapshot);
  }

  public List<JobRecord> listJobs(int limit, int offset) {
    validatePage(limit, offset);
    return jobs.list(limit, offset).stream().map(JobEntity::snapshot).toList();
  }

  /**
   * Claims one pending or expired job without waiting for another claimant's row lock. Reclaims
   * receive a new fencing token. An expired third attempt becomes FAILED.
   */
  @Transactional
  public Optional<JobRecord> claimNext(Instant now, Duration leaseDuration) {
    Instant timestamp = databaseTime(now);
    Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
    if (leaseDuration.isNegative() || leaseDuration.isZero()) {
      throw new IllegalArgumentException("leaseDuration must be positive");
    }
    Instant leaseUntil;
    try {
      leaseUntil = timestamp.plus(leaseDuration).truncatedTo(ChronoUnit.MICROS);
    } catch (DateTimeException | ArithmeticException exception) {
      throw new IllegalArgumentException("leaseDuration exceeds timestamp range", exception);
    }
    if (!leaseUntil.isAfter(timestamp)) {
      throw new IllegalArgumentException("leaseDuration must be at least one microsecond");
    }
    jobs.failExhausted(timestamp);
    return jobs.lockNext(timestamp)
        .map(
            job -> {
              job.status = JobStatus.RUNNING;
              job.leaseToken = UUID.randomUUID();
              job.leaseUntil = leaseUntil;
              job.updatedAt = timestamp;
              job.attempts++;
              return jobs.saveAndFlush(job).snapshot();
            });
  }

  /** Atomically persists the result and SUCCEEDED only for the current, unexpired lease holder. */
  @Transactional
  public boolean complete(UUID id, UUID leaseToken, String resultJson, Instant now) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(leaseToken, "leaseToken must not be null");
    requireText(resultJson, "resultJson");
    return jobs.complete(id, leaseToken, resultJson, databaseTime(now)) == 1;
  }

  @Transactional
  public boolean fail(UUID id, UUID leaseToken, String failureCode, Instant now) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(leaseToken, "leaseToken must not be null");
    requireText(failureCode, "failureCode");
    if (!failureCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
      throw new IllegalArgumentException(
          "failureCode must contain uppercase letters, digits, and underscores, starting with a letter");
    }
    return jobs.fail(id, leaseToken, failureCode, databaseTime(now)) == 1;
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static Instant databaseTime(Instant now) {
    return Objects.requireNonNull(now, "now must not be null").truncatedTo(ChronoUnit.MICROS);
  }

  private static void validatePage(int limit, int offset) {
    if (limit < 1 || limit > 100 || offset < 0) {
      throw new IllegalArgumentException(
          "limit must be between 1 and 100; offset must be nonnegative");
    }
  }
}
