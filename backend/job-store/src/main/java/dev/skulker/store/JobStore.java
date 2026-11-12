package dev.skulker.store;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private final OutboxRepository outbox;
  private final BatchRepository batches;
  private final BatchJobRepository batchJobs;

  JobStore(
      DatasetRepository datasets,
      JobRepository jobs,
      OutboxRepository outbox,
      BatchRepository batches,
      BatchJobRepository batchJobs) {
    this.datasets = datasets;
    this.jobs = jobs;
    this.outbox = outbox;
    this.batches = batches;
    this.batchJobs = batchJobs;
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
    return createJobRow(datasetId, configurationJson, timestamp).snapshot();
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
    Instant leaseUntil = leaseDeadline(timestamp, leaseDuration);
    recoverExpiredJobs(timestamp, 100);
    return jobs.lockNext(timestamp).map(job -> claim(job, timestamp, leaseUntil));
  }

  @Transactional
  public Optional<JobRecord> claimJob(UUID id, Instant now, Duration leaseDuration) {
    Objects.requireNonNull(id, "id must not be null");
    Instant timestamp = databaseTime(now);
    Instant leaseUntil = leaseDeadline(timestamp, leaseDuration);
    return jobs.lockClaimable(id, timestamp)
        .flatMap(
            job -> {
              if (job.attempts >= 3) {
                terminalFailure(job, "MAX_ATTEMPTS_EXCEEDED", timestamp);
                return Optional.empty();
              }
              return Optional.of(claim(job, timestamp, leaseUntil));
            });
  }

  @Transactional
  public Optional<OutboxRecord> claimOutbox(Instant now, Duration leaseDuration) {
    Instant timestamp = databaseTime(now);
    Instant leaseUntil = leaseDeadline(timestamp, leaseDuration);
    for (int count = 0; count < 100; count++) {
      Optional<OutboxEntity> exhausted = outbox.lockExhausted(timestamp);
      if (exhausted.isEmpty()) {
        break;
      }
      exhaustPublication(exhausted.orElseThrow(), timestamp);
    }
    return outbox
        .lockNext(timestamp)
        .map(
            entry -> {
              entry.attempts++;
              entry.lastAttemptAt = timestamp;
              entry.leaseToken = UUID.randomUUID();
              entry.leaseUntil = leaseUntil;
              return outbox.saveAndFlush(entry).snapshot();
            });
  }

  @Transactional
  public boolean markPublished(UUID outboxId, UUID leaseToken, Instant now) {
    Objects.requireNonNull(outboxId, "outboxId must not be null");
    Objects.requireNonNull(leaseToken, "leaseToken must not be null");
    Instant timestamp = databaseTime(now);
    Optional<OutboxEntity> owned = outbox.lockOwned(outboxId, leaseToken, timestamp);
    if (owned.isEmpty()) {
      return false;
    }
    OutboxEntity entry = owned.orElseThrow();
    entry.publishedAt = timestamp;
    clearPublicationLease(entry);
    outbox.saveAndFlush(entry);
    if (entry.kind == OutboxKind.BACKTEST) {
      jobs.markQueued(entry.jobId, entry.jobAttempt, timestamp);
    }
    return true;
  }

  @Transactional
  public boolean retryPublication(UUID outboxId, UUID leaseToken, Instant now, Duration backoff) {
    Objects.requireNonNull(outboxId, "outboxId must not be null");
    Objects.requireNonNull(leaseToken, "leaseToken must not be null");
    Instant timestamp = databaseTime(now);
    Instant availableAt = retryDeadline(timestamp, backoff);
    Optional<OutboxEntity> owned = outbox.lockOwned(outboxId, leaseToken, timestamp);
    if (owned.isEmpty()) {
      return false;
    }
    OutboxEntity entry = owned.orElseThrow();
    if (entry.attempts >= 10) {
      exhaustPublication(entry, timestamp);
    } else {
      entry.availableAt = availableAt;
      clearPublicationLease(entry);
      outbox.saveAndFlush(entry);
    }
    return true;
  }

  @Transactional
  public boolean retryOrFail(
      UUID jobId, UUID leaseToken, String failureCode, Instant now, Duration backoff) {
    Objects.requireNonNull(jobId, "jobId must not be null");
    Objects.requireNonNull(leaseToken, "leaseToken must not be null");
    validateFailureCode(failureCode);
    Instant timestamp = databaseTime(now);
    Instant availableAt = retryDeadline(timestamp, backoff);
    Optional<JobEntity> owned = jobs.lockOwned(jobId, leaseToken, timestamp);
    if (owned.isEmpty()) {
      return false;
    }
    retryOrTerminate(owned.orElseThrow(), failureCode, timestamp, availableAt);
    return true;
  }

  @Transactional
  public int recoverExpiredJobs(Instant now, int limit) {
    Instant timestamp = databaseTime(now);
    validatePage(limit, 0);
    List<JobEntity> expired = jobs.lockExpired(timestamp, limit);
    for (JobEntity job : expired) {
      retryOrTerminate(job, "MAX_ATTEMPTS_EXCEEDED", timestamp, timestamp);
    }
    return expired.size();
  }

  @Transactional
  public BatchRecord createBatch(List<JobSpecification> specifications, Instant now) {
    Objects.requireNonNull(specifications, "specifications must not be null");
    if (specifications.isEmpty() || specifications.size() > 100) {
      throw new IllegalArgumentException("a batch must contain between 1 and 100 jobs");
    }
    List<JobSpecification> inputs = List.copyOf(specifications);
    Instant timestamp = databaseTime(now);
    for (JobSpecification specification : inputs) {
      if (!datasets.existsById(specification.datasetId())) {
        throw new IllegalArgumentException("dataset does not exist: " + specification.datasetId());
      }
    }
    BatchEntity batch = batches.saveAndFlush(new BatchEntity(UUID.randomUUID(), timestamp));
    List<UUID> jobIds = new ArrayList<>(inputs.size());
    for (int index = 0; index < inputs.size(); index++) {
      JobSpecification specification = inputs.get(index);
      JobEntity job =
          createJobRow(specification.datasetId(), specification.configurationJson(), timestamp);
      batchJobs.saveAndFlush(new BatchJobEntity(batch.id, job.id, index));
      jobIds.add(job.id);
    }
    return new BatchRecord(batch.id, batch.createdAt, jobIds);
  }

  public Optional<BatchRecord> findBatch(UUID id) {
    Objects.requireNonNull(id, "id must not be null");
    return batches
        .findById(id)
        .map(
            batch ->
                new BatchRecord(
                    batch.id,
                    batch.createdAt,
                    batchJobs.findByBatchIdOrderByPosition(id).stream()
                        .map(member -> member.jobId)
                        .toList()));
  }

  public Map<UUID, JobStatus> batchStatuses(UUID batchId) {
    Objects.requireNonNull(batchId, "batchId must not be null");
    Map<UUID, JobStatus> statuses = new LinkedHashMap<>();
    for (BatchStatusProjection status : batchJobs.statuses(batchId)) {
      statuses.put(status.getId(), JobStatus.valueOf(status.getStatus()));
    }
    return Map.copyOf(statuses);
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
    validateFailureCode(failureCode);
    return jobs.fail(id, leaseToken, failureCode, databaseTime(now)) == 1;
  }

  private JobEntity createJobRow(UUID datasetId, String configurationJson, Instant now) {
    JobEntity job =
        jobs.saveAndFlush(new JobEntity(UUID.randomUUID(), datasetId, configurationJson, now));
    enqueue(job, OutboxKind.BACKTEST, null, now, now);
    return job;
  }

  private JobRecord claim(JobEntity job, Instant now, Instant leaseUntil) {
    job.status = JobStatus.RUNNING;
    job.leaseToken = UUID.randomUUID();
    job.leaseUntil = leaseUntil;
    job.updatedAt = now;
    job.attempts++;
    return jobs.saveAndFlush(job).snapshot();
  }

  private void retryOrTerminate(
      JobEntity job, String failureCode, Instant now, Instant availableAt) {
    if (job.attempts >= 3) {
      terminalFailure(job, failureCode, now);
    } else {
      job.status = JobStatus.PENDING;
      job.failureCode = null;
      job.resultJson = null;
      job.leaseToken = null;
      job.leaseUntil = null;
      job.updatedAt = now;
      job.availableAt = availableAt;
      jobs.saveAndFlush(job);
      enqueue(job, OutboxKind.BACKTEST, null, now, availableAt);
    }
  }

  private void terminalFailure(JobEntity job, String failureCode, Instant now) {
    job.status = JobStatus.FAILED;
    job.failureCode = failureCode;
    job.resultJson = null;
    job.leaseToken = null;
    job.leaseUntil = null;
    job.updatedAt = now;
    jobs.saveAndFlush(job);
    enqueue(job, OutboxKind.DEAD_LETTER, failureCode, now, now);
  }

  private void enqueue(
      JobEntity job, OutboxKind kind, String failureCode, Instant now, Instant availableAt) {
    outbox.saveAndFlush(
        new OutboxEntity(job.id, kind, failureCode, job.attempts, now, availableAt));
  }

  private void exhaustPublication(OutboxEntity entry, Instant now) {
    entry.failedAt = now;
    clearPublicationLease(entry);
    outbox.saveAndFlush(entry);
    if (entry.kind == OutboxKind.BACKTEST) {
      jobs.lockForPublicationFailure(entry.jobId, entry.jobAttempt, now)
          .ifPresent(job -> terminalFailure(job, "PUBLICATION_FAILED", now));
    }
  }

  private static void clearPublicationLease(OutboxEntity entry) {
    entry.leaseToken = null;
    entry.leaseUntil = null;
  }

  private static Instant leaseDeadline(Instant now, Duration duration) {
    Objects.requireNonNull(duration, "leaseDuration must not be null");
    if (duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException("leaseDuration must be positive");
    }
    Instant deadline = retryDeadline(now, duration);
    if (!deadline.isAfter(now)) {
      throw new IllegalArgumentException("leaseDuration must be at least one microsecond");
    }
    return deadline;
  }

  private static Instant retryDeadline(Instant now, Duration backoff) {
    Objects.requireNonNull(backoff, "backoff must not be null");
    if (backoff.isNegative()) {
      throw new IllegalArgumentException("backoff must not be negative");
    }
    try {
      return now.plus(backoff).truncatedTo(ChronoUnit.MICROS);
    } catch (DateTimeException | ArithmeticException exception) {
      throw new IllegalArgumentException("duration exceeds timestamp range", exception);
    }
  }

  private static void validateFailureCode(String failureCode) {
    requireText(failureCode, "failureCode");
    if (!failureCode.matches("[A-Z][A-Z0-9_]{0,127}")) {
      throw new IllegalArgumentException(
          "failureCode must contain uppercase letters, digits, and underscores, starting with a letter");
    }
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
