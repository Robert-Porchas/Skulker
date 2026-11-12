"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { isActive } from "@/lib/format";
import type { BacktestResult, Batch, Dataset, Job } from "@/lib/types";
import { DatasetUpload, RunSetup } from "./ResearchForms";
import { ComparisonPanel, ResultsPanel, type ComparedResult } from "./Results";
import {
  EmptyState,
  ErrorMessage,
  Icon,
  Pagination,
  StatusBadge,
  message,
  shortId,
  timestamp,
} from "./Primitives";

function revealSection(id: string) {
  const section = document.getElementById(id);
  section?.focus({ preventScroll: true });
  section?.scrollIntoView({ block: "start" });
}

export default function Dashboard() {
  const [datasets, setDatasets] = useState<Dataset[]>([]),
    [knownDatasets, setKnownDatasets] = useState<Record<string, Dataset>>({}),
    [jobs, setJobs] = useState<Job[]>([]);
  const [datasetOffset, setDatasetOffset] = useState(0),
    [jobOffset, setJobOffset] = useState(0),
    [revision, setRevision] = useState(0);
  const [datasetsLoading, setDatasetsLoading] = useState(true),
    [jobsLoading, setJobsLoading] = useState(true),
    [datasetError, setDatasetError] = useState<string | null>(null),
    [jobError, setJobError] = useState<string | null>(null);
  const [submittedJob, setSubmittedJob] = useState<Job | null>(null),
    [batches, setBatches] = useState<Batch[]>([]),
    [pollError, setPollError] = useState<string | null>(null);
  const [selectedJob, setSelectedJob] = useState<string | null>(null),
    [result, setResult] = useState<BacktestResult | null>(null),
    [resultLoading, setResultLoading] = useState(false),
    [resultError, setResultError] = useState<string | null>(null);
  const [compareIds, setCompareIds] = useState<string[]>([]),
    [comparisons, setComparisons] = useState<ComparedResult[]>([]),
    [comparing, setComparing] = useState(false),
    [compareError, setCompareError] = useState<string | null>(null);
  const resultCache = useRef(new Map<string, BacktestResult>()),
    resultRequest = useRef<AbortController | null>(null);
  const activeBatchIds = batches
    .filter((batch) => isActive(batch.status))
    .map((batch) => batch.id)
    .join(",");
  const activeJobIds = jobs
    .filter((job) => isActive(job.status))
    .map((job) => job.id)
    .join(",");
  const submittedId =
    submittedJob && isActive(submittedJob.status) ? submittedJob.id : null;

  useEffect(() => {
    if (selectedJob) revealSection("results");
  }, [selectedJob]);
  useEffect(() => {
    if (comparisons.length) revealSection("comparison");
  }, [comparisons]);

  useEffect(() => {
    const controller = new AbortController();
    api
      .datasets(datasetOffset, controller.signal)
      .then((items) => {
        setDatasets(items);
        setKnownDatasets((previous) => ({
          ...previous,
          ...Object.fromEntries(items.map((dataset) => [dataset.id, dataset])),
        }));
        setDatasetError(null);
      })
      .catch((error) => {
        if (!controller.signal.aborted) setDatasetError(message(error));
      })
      .finally(() => {
        if (!controller.signal.aborted) setDatasetsLoading(false);
      });
    return () => controller.abort();
  }, [datasetOffset, revision]);
  useEffect(() => {
    const controller = new AbortController();
    api
      .jobs(jobOffset, controller.signal)
      .then((items) => {
        setJobs(items);
        setJobError(null);
        setPollError(null);
      })
      .catch((error) => {
        if (!controller.signal.aborted) setJobError(message(error));
      })
      .finally(() => {
        if (!controller.signal.aborted) setJobsLoading(false);
      });
    return () => controller.abort();
  }, [jobOffset, revision]);
  useEffect(() => {
    if (!activeJobIds && !activeBatchIds && !submittedId) return;
    const controller = new AbortController();
    let pending = false;
    const timer = setInterval(async () => {
      if (pending) return;
      pending = true;
      try {
        const [updatedJobs, updatedBatches, updatedSubmission] =
          await Promise.all([
            api.jobs(jobOffset, controller.signal),
            Promise.all(
              activeBatchIds
                ? activeBatchIds
                    .split(",")
                    .map((id) => api.batch(id, controller.signal))
                : [],
            ),
            submittedId
              ? api.job(submittedId, controller.signal)
              : Promise.resolve(null),
          ]);
        if (controller.signal.aborted) return;
        setJobs(updatedJobs);
        setBatches((previous) =>
          previous.map(
            (batch) =>
              updatedBatches.find((updated) => updated.id === batch.id) ??
              batch,
          ),
        );
        if (updatedSubmission) setSubmittedJob(updatedSubmission);
        setPollError(null);
        setJobError(null);
      } catch (error) {
        if (!controller.signal.aborted) setPollError(message(error));
      } finally {
        pending = false;
      }
    }, 3000);
    return () => {
      controller.abort();
      clearInterval(timer);
    };
  }, [activeJobIds, activeBatchIds, submittedId, jobOffset]);
  useEffect(() => () => resultRequest.current?.abort(), []);

  function refresh() {
    setDatasetsLoading(true);
    setJobsLoading(true);
    setRevision((previous) => previous + 1);
  }
  function uploaded(dataset: Dataset) {
    setKnownDatasets((previous) => ({ ...previous, [dataset.id]: dataset }));
    setDatasetOffset(0);
    setDatasetsLoading(true);
    setRevision((previous) => previous + 1);
  }
  function submitted(job: Job) {
    setSubmittedJob(job);
    setJobOffset(0);
    setJobsLoading(true);
    setRevision((previous) => previous + 1);
  }
  function swept(batch: Batch) {
    setBatches((previous) => [batch, ...previous]);
    setJobOffset(0);
    setJobsLoading(true);
    setRevision((previous) => previous + 1);
  }
  function cached(id: string) {
    const value = resultCache.current.get(id);
    if (value) {
      resultCache.current.delete(id);
      resultCache.current.set(id, value);
    }
    return value;
  }
  function remember(id: string, value: BacktestResult) {
    resultCache.current.delete(id);
    resultCache.current.set(id, value);
    while (resultCache.current.size > 8) {
      const oldest = resultCache.current.keys().next().value;
      if (oldest !== undefined) resultCache.current.delete(oldest);
    }
  }
  async function viewResult(id: string) {
    if (selectedJob === id) revealSection("results");
    resultRequest.current?.abort();
    const controller = new AbortController();
    resultRequest.current = controller;
    setSelectedJob(id);
    setResultError(null);
    setResult(null);
    setResultLoading(true);
    document
      .getElementById("results")
      ?.scrollIntoView({ behavior: "smooth", block: "start" });
    try {
      const loaded = cached(id) ?? (await api.result(id, controller.signal));
      if (!controller.signal.aborted) {
        remember(id, loaded);
        setResult(loaded);
      }
    } catch (error) {
      if (!controller.signal.aborted) setResultError(message(error));
    } finally {
      if (!controller.signal.aborted) setResultLoading(false);
    }
  }
  function toggleCompare(id: string) {
    setCompareError(null);
    setComparisons([]);
    setCompareIds((previous) =>
      previous.includes(id)
        ? previous.filter((item) => item !== id)
        : previous.length < 4
          ? [...previous, id]
          : previous,
    );
  }
  async function compare() {
    setComparing(true);
    setCompareError(null);
    setComparisons([]);
    try {
      const loaded = await Promise.all(
        compareIds.map(async (id) => {
          const value = cached(id) ?? (await api.result(id));
          remember(id, value);
          return { id, result: value };
        }),
      );
      setComparisons(loaded);
    } catch (error) {
      setCompareError(message(error));
    } finally {
      setComparing(false);
    }
  }

  const connection =
    datasetError || jobError
      ? "Connection issue"
      : datasetsLoading && jobsLoading
        ? "Connecting"
        : "API connected";
  const datasetOptions = Object.values(knownDatasets);
  const activeCount = jobs.filter((job) => isActive(job.status)).length;
  return (
    <div className="workspace" id="overview">
      <a className="skip-link" href="#main-content">
        Skip to workspace
      </a>
      <aside className="sidebar">
        <a className="brand" href="#overview" aria-label="Skulker workspace">
          <span className="brand-mark">
            <Icon name="chart" size={23} />
          </span>
          <span>
            skulker<span className="brand-period">.</span>
          </span>
        </a>
        <div className="workspace-name">
          <span className="workspace-avatar">SR</span>
          <div>
            Strategy research<small>Local workspace</small>
          </div>
          <span className="workspace-menu">⌘</span>
        </div>
        <p className="nav-label">WORKSPACE</p>
        <nav className="side-nav" aria-label="Workspace navigation">
          <a href="#overview" className="nav-current">
            <Icon name="grid" />
            Overview
            <span className="nav-dot" />
          </a>
          <a href="#datasets">
            <Icon name="database" />
            Datasets
          </a>
          <a href="#configure">
            <Icon name="activity" />
            Configure a run
          </a>
          <a href="#jobs">
            <Icon name="layers" />
            Backtests
          </a>
          <a href="#results">
            <Icon name="chart" />
            Results
          </a>
        </nav>
        <div className="sidebar-bottom">
          <div className="research-tag">
            <span className="small-dot" />
            HISTORICAL RESEARCH
          </div>
          <p>
            Clear assumptions.
            <br />
            Reproducible outcomes.
          </p>
          <span className="sidebar-version">Skulker research platform</span>
        </div>
      </aside>
      <main id="main-content" className="main-content">
        <header className="topbar">
          <div className="breadcrumb">
            Workspace<span>/</span>
            <strong>Strategy research</strong>
          </div>
          <div
            className="connection"
            data-connected={!datasetError && !jobError}
          >
            <span className="small-dot" />
            {connection}
          </div>
        </header>
        <div className="page-content">
          <section className="page-heading">
            <div>
              <p className="eyebrow">THE RESEARCH BENCH</p>
              <h1>
                Backtest workspace<span>.</span>
              </h1>
              <p>Turn historical data into a strategy you can examine.</p>
            </div>
            <button
              className="button button-outline"
              onClick={refresh}
              disabled={datasetsLoading || jobsLoading}
            >
              <Icon name="refresh" size={15} />
              Refresh data
            </button>
          </section>
          <section
            className="summary-strip"
            aria-label="Loaded workspace summary"
          >
            <div>
              <span className="summary-icon">
                <Icon name="database" />
              </span>
              <div>
                <p>Datasets loaded</p>
                <strong>
                  {datasetsLoading && !datasetOptions.length
                    ? "—"
                    : datasetOptions.length}
                </strong>
                <small>Across visited pages</small>
              </div>
            </div>
            <div>
              <span className="summary-icon teal">
                <Icon name="layers" />
              </span>
              <div>
                <p>Visible backtests</p>
                <strong>
                  {jobsLoading && !jobs.length ? "—" : jobs.length}
                </strong>
                <small>Current page</small>
              </div>
            </div>
            <div>
              <span className="summary-icon amber">
                <Icon name="activity" />
              </span>
              <div>
                <p>In progress</p>
                <strong>
                  {jobsLoading && !jobs.length ? "—" : activeCount}
                </strong>
                <small>On this page</small>
              </div>
            </div>
            <div>
              <span className="summary-icon lavender">
                <Icon name="chart" />
              </span>
              <div>
                <p>Selected to compare</p>
                <strong>
                  {compareIds.length}
                  <span>/ 4</span>
                </strong>
                <small>Succeeded runs only</small>
              </div>
            </div>
          </section>
          <div className="research-grid">
            <section
              className="panel datasets-panel"
              id="datasets"
              aria-labelledby="datasets-heading"
              aria-busy={datasetsLoading}
            >
              <div className="panel-heading">
                <div>
                  <span className="eyebrow">01 / YOUR INPUTS</span>
                  <h2 id="datasets-heading">Dataset library</h2>
                </div>
                <span className="count-pill">OHLCV · daily</span>
              </div>
              {datasetsLoading ? (
                <div className="loading-state" role="status">
                  <span className="spinner" />
                  Loading datasets…
                </div>
              ) : datasetError ? (
                <div className="panel-error">
                  <ErrorMessage>{datasetError}</ErrorMessage>
                  <button
                    className="button button-small button-outline"
                    onClick={refresh}
                  >
                    Retry datasets
                  </button>
                </div>
              ) : !datasets.length ? (
                <EmptyState
                  icon="database"
                  title="Research starts with good data."
                >
                  Upload a historical OHLCV CSV to build your first backtest.
                  Your source data remains available to download.
                </EmptyState>
              ) : (
                <div className="dataset-list">
                  {datasets.map((dataset) => (
                    <article key={dataset.id} className="dataset-item">
                      <span className="dataset-emblem">
                        {dataset.symbol.slice(0, 2)}
                      </span>
                      <div className="dataset-detail">
                        <h3>
                          {dataset.symbol}
                          <span>DAILY</span>
                        </h3>
                        <p>
                          {dataset.startDate} <span>→</span> {dataset.endDate}
                        </p>
                        <small>
                          {dataset.barCount.toLocaleString()} bars{" "}
                          <span>·</span>
                          <code title={dataset.id}>{shortId(dataset.id)}</code>
                        </small>
                      </div>
                      <a
                        className="icon-button"
                        href={api.datasetDownload(dataset.id)}
                        download={`${dataset.symbol}.csv`}
                        aria-label={`Download ${dataset.symbol} dataset ${dataset.id}`}
                        title="Download original CSV"
                      >
                        <Icon name="download" size={17} />
                      </a>
                    </article>
                  ))}
                </div>
              )}
              <Pagination
                name="Dataset"
                offset={datasetOffset}
                count={datasets.length}
                loading={datasetsLoading}
                onChange={(offset) => {
                  setDatasetOffset(offset);
                  setDatasetsLoading(true);
                }}
              />
              <DatasetUpload onUploaded={uploaded} />
            </section>
            <RunSetup
              datasets={datasetOptions}
              onJob={submitted}
              onBatch={swept}
            />
          </div>
          {(submittedJob || batches.length > 0) && (
            <section
              className="activity-panel"
              aria-label="Submitted research progress"
            >
              <div className="activity-heading">
                <Icon name="activity" size={17} />
                <h2>Research in motion</h2>
                <span>Sweeps tracked in this session</span>
              </div>
              {submittedJob && (
                <div className="submission-row">
                  <span>
                    Latest backtest <code>{shortId(submittedJob.id)}</code>
                  </span>
                  <StatusBadge status={submittedJob.status} />
                  {submittedJob.status === "SUCCEEDED" && (
                    <button
                      className="button button-small button-quiet"
                      onClick={() => viewResult(submittedJob.id)}
                    >
                      View result
                      <Icon name="arrow" size={13} />
                    </button>
                  )}
                  {submittedJob.status === "FAILED" && (
                    <span className="failure-code">
                      {submittedJob.failureCode ?? "Execution failed"}
                    </span>
                  )}
                </div>
              )}
              {batches.map((batch) => (
                <div className="batch-row" key={batch.id}>
                  <div>
                    <strong>
                      Parameter sweep <code>{shortId(batch.id)}</code>
                    </strong>
                    <small>
                      {batch.succeeded} succeeded · {batch.failed} failed ·{" "}
                      {batch.total} total
                    </small>
                  </div>
                  <div className="batch-progress">
                    <progress
                      aria-label={`Sweep ${batch.id} completed tasks`}
                      value={batch.succeeded + batch.failed}
                      max={batch.total}
                    />
                    <span>
                      {batch.succeeded + batch.failed}/{batch.total}
                    </span>
                  </div>
                  <StatusBadge status={batch.status} />
                </div>
              ))}
            </section>
          )}
          <section
            className="panel jobs-panel"
            id="jobs"
            aria-labelledby="jobs-heading"
            aria-busy={jobsLoading}
          >
            <div className="panel-heading">
              <div>
                <span className="eyebrow">03 / EXECUTION</span>
                <h2 id="jobs-heading">Recent backtests</h2>
              </div>
              <span className="polling-note">
                <span
                  className={
                    activeJobIds || activeBatchIds || submittedId
                      ? "small-dot pulse"
                      : "small-dot"
                  }
                />
                {activeJobIds || activeBatchIds || submittedId
                  ? "Active runs refresh every 3s"
                  : "Ready for your next hypothesis"}
              </span>
            </div>
            {pollError && (
              <div className="panel-error">
                <ErrorMessage>
                  Progress refresh interrupted. {pollError}
                </ErrorMessage>
              </div>
            )}
            {jobsLoading ? (
              <div className="loading-state" role="status">
                <span className="spinner" />
                Loading backtests…
              </div>
            ) : jobError ? (
              <div className="panel-error">
                <ErrorMessage>{jobError}</ErrorMessage>
                <button
                  className="button button-small button-outline"
                  onClick={refresh}
                >
                  Retry backtests
                </button>
              </div>
            ) : !jobs.length ? (
              <EmptyState icon="activity" title="Your next idea belongs here.">
                Configure a single run or a parameter sweep. Submitted work will
                appear here with its recorded execution status.
              </EmptyState>
            ) : (
              <div className="table-scroll">
                <table className="jobs-table">
                  <caption className="sr-only">Recent backtests</caption>
                  <thead>
                    <tr>
                      <th className="selection-column">
                        <span className="sr-only">Compare</span>
                      </th>
                      <th>Run</th>
                      <th>Dataset</th>
                      <th>Status</th>
                      <th>Submitted · UTC</th>
                      <th>Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {jobs.map((job) => (
                      <tr key={job.id}>
                        <td>
                          <input
                            type="checkbox"
                            aria-label={`Compare job ${job.id}`}
                            checked={compareIds.includes(job.id)}
                            disabled={
                              comparing ||
                              job.status !== "SUCCEEDED" ||
                              (!compareIds.includes(job.id) &&
                                compareIds.length >= 4)
                            }
                            onChange={() => toggleCompare(job.id)}
                          />
                        </td>
                        <td>
                          <code title={job.id}>{shortId(job.id)}</code>
                        </td>
                        <td>
                          <strong>
                            {knownDatasets[job.datasetId]?.symbol ?? "Dataset"}
                          </strong>
                          {!knownDatasets[job.datasetId] && (
                            <small
                              className="cell-subtext"
                              title={job.datasetId}
                            >
                              {shortId(job.datasetId)}
                            </small>
                          )}
                        </td>
                        <td>
                          <StatusBadge status={job.status} />
                          {job.failureCode && (
                            <small className="cell-subtext failure-code">
                              {job.failureCode}
                            </small>
                          )}
                        </td>
                        <td className="time-cell">
                          <time dateTime={job.createdAt}>
                            {timestamp(job.createdAt)}
                          </time>
                        </td>
                        <td>
                          {job.status === "SUCCEEDED" ? (
                            <button
                              className="button button-small button-quiet"
                              onClick={() => viewResult(job.id)}
                            >
                              View result
                              <Icon name="arrow" size={13} />
                            </button>
                          ) : (
                            <span className="muted">
                              {isActive(job.status)
                                ? "Awaiting completion"
                                : "No result"}
                            </span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <div className="jobs-footer">
              <div className="compare-actions">
                <button
                  className="button button-outline button-small"
                  disabled={!compareIds.length || comparing}
                  onClick={compare}
                >
                  <Icon name="chart" size={15} />
                  {comparing ? "Loading comparison…" : "Compare selected"}
                </button>
                <span>{compareIds.length}/4 selected</span>
                {compareIds.length > 0 && (
                  <button
                    className="text-button"
                    disabled={comparing}
                    onClick={() => {
                      setCompareIds([]);
                      setComparisons([]);
                      setCompareError(null);
                    }}
                  >
                    Clear
                  </button>
                )}
              </div>
              <Pagination
                name="Backtest"
                offset={jobOffset}
                count={jobs.length}
                loading={jobsLoading}
                onChange={(offset) => {
                  setJobOffset(offset);
                  setJobsLoading(true);
                }}
              />
            </div>
            {compareError && (
              <div className="panel-error">
                <ErrorMessage>{compareError}</ErrorMessage>
              </div>
            )}
          </section>
          <ComparisonPanel results={comparisons} onView={viewResult} />
          <ResultsPanel
            key={selectedJob}
            selected={selectedJob}
            result={result}
            loading={resultLoading}
            error={resultError}
            onRetry={() => {
              if (selectedJob) void viewResult(selectedJob);
            }}
          />
          <footer className="page-footer">
            <span>
              <span className="footer-mark">S/</span>Built for questions worth
              testing.
            </span>
            <span>Historical research only · No live trading</span>
          </footer>
        </div>
      </main>
    </div>
  );
}
