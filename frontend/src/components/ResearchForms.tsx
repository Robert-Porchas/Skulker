"use client";

import { useState, type FormEvent } from "react";
import { api } from "@/lib/api";
import { parseWindows, validMoney } from "@/lib/format";
import type { Batch, Dataset, Job } from "@/lib/types";
import { ErrorMessage, Icon, message } from "./Primitives";

export function DatasetUpload({
  onUploaded,
}: {
  onUploaded: (dataset: Dataset) => void;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<string | null>(null),
    [notice, setNotice] = useState<string | null>(null);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget,
      fields = new FormData(form),
      symbol = String(fields.get("symbol") ?? ""),
      file = fields.get("file");
    setError(null);
    setNotice(null);
    if (!/^[A-Za-z0-9._-]{1,32}$/.test(symbol)) {
      setError(
        "Use 1–32 letters, digits, periods, underscores, or hyphens for the symbol.",
      );
      return;
    }
    if (!(file instanceof File) || !file.size) {
      setError("Choose a nonempty historical CSV file.");
      return;
    }
    if (file.size > 2 * 1024 * 1024) {
      setError("CSV uploads must not exceed 2 MiB.");
      return;
    }
    setBusy(true);
    try {
      const dataset = await api.upload(symbol, file);
      onUploaded(dataset);
      setNotice(
        `${dataset.symbol} uploaded · ${dataset.barCount.toLocaleString()} daily bars.`,
      );
      form.reset();
    } catch (failure) {
      setError(message(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <form
      className="upload-form"
      onSubmit={submit}
      aria-label="Upload historical dataset"
    >
      <div className="upload-intro">
        <Icon name="upload" />
        <div>
          <h3>Add historical data</h3>
          <p>Your CSV, your assumptions. Up to 2 MiB / 10,000 daily bars.</p>
        </div>
      </div>
      <fieldset disabled={busy}>
        <div className="upload-fields">
          <label>
            Symbol
            <input
              name="symbol"
              placeholder="e.g. AAPL"
              required
              maxLength={32}
              autoComplete="off"
              pattern="[A-Za-z0-9._\-]+"
            />
          </label>
          <label className="file-field">
            CSV file
            <input type="file" name="file" accept=".csv,text/csv" required />
          </label>
          <button className="button button-outline" type="submit">
            {busy ? "Uploading…" : "Upload dataset"}
            <Icon name="plus" size={15} />
          </button>
        </div>
      </fieldset>
      <p className="field-help">
        Columns: Date, Open, High, Low, Close, Volume. Optional: Adj Close.
        Dates must be unique and chronological.
      </p>
      {error && <ErrorMessage>{error}</ErrorMessage>}
      {notice && (
        <p className="success-message" role="status">
          <Icon name="check" size={16} />
          {notice}
        </p>
      )}
    </form>
  );
}

function AmountFields({
  prefix,
  capital,
  fee,
  onCapital,
  onFee,
}: {
  prefix: string;
  capital: string;
  fee: string;
  onCapital: (value: string) => void;
  onFee: (value: string) => void;
}) {
  return (
    <div className="form-grid">
      <label htmlFor={`${prefix}-capital`}>
        Initial capital
        <input
          id={`${prefix}-capital`}
          type="text"
          inputMode="decimal"
          value={capital}
          onChange={(event) => onCapital(event.target.value)}
          required
          autoComplete="off"
        />
      </label>
      <label htmlFor={`${prefix}-fee`}>
        Transaction fee
        <input
          id={`${prefix}-fee`}
          type="text"
          inputMode="decimal"
          value={fee}
          onChange={(event) => onFee(event.target.value)}
          required
          autoComplete="off"
        />
      </label>
    </div>
  );
}

function SingleForm({
  datasets,
  onSubmitted,
}: {
  datasets: Dataset[];
  onSubmitted: (job: Job) => void;
}) {
  const [datasetId, setDatasetId] = useState(""),
    [short, setShort] = useState("10"),
    [long, setLong] = useState("50"),
    [capital, setCapital] = useState("10000"),
    [fee, setFee] = useState("1");
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<string | null>(null),
    [success, setSuccess] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setSuccess(false);
    try {
      if (!datasetId)
        throw new Error("Choose a dataset before running a backtest.");
      const shorts = parseWindows(short),
        longs = parseWindows(long, 2);
      if (shorts.length !== 1 || longs.length !== 1 || shorts[0] >= longs[0])
        throw new Error("Short window must be smaller than long window.");
      if (!validMoney(capital, true) || !validMoney(fee))
        throw new Error(
          "Enter positive initial capital and a nonnegative fee, with at most 24 integer and 8 decimal digits.",
        );
      setBusy(true);
      const job = await api.submitJob({
        datasetId,
        shortWindow: shorts[0],
        longWindow: longs[0],
        initialCapital: capital,
        transactionFee: fee,
      });
      onSubmitted(job);
      setSuccess(true);
    } catch (failure) {
      setError(message(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <form
      className="strategy-form"
      onSubmit={submit}
      aria-label="Single backtest"
    >
      <fieldset disabled={busy}>
        <label>
          Dataset
          <select
            value={datasetId}
            onChange={(event) => setDatasetId(event.target.value)}
            required
          >
            <option value="">Select a historical dataset</option>
            {datasets.map((dataset) => (
              <option key={dataset.id} value={dataset.id}>
                {dataset.symbol} · {dataset.startDate} — {dataset.endDate}
              </option>
            ))}
          </select>
        </label>
        <div className="form-grid">
          <label>
            Short window
            <input
              type="number"
              min="1"
              max="10000"
              step="1"
              required
              value={short}
              onChange={(event) => setShort(event.target.value)}
            />
            <span className="input-unit">trading bars</span>
          </label>
          <label>
            Long window
            <input
              type="number"
              min="2"
              max="10000"
              step="1"
              required
              value={long}
              onChange={(event) => setLong(event.target.value)}
            />
            <span className="input-unit">trading bars</span>
          </label>
        </div>
        <AmountFields
          prefix="single"
          capital={capital}
          fee={fee}
          onCapital={setCapital}
          onFee={setFee}
        />
        <p className="field-help">
          Amounts use the dataset&apos;s quote units. The fixed fee applies to
          each executed buy and sell.
        </p>
        <button
          type="submit"
          className="button button-primary button-full"
          disabled={!datasets.length}
        >
          {busy ? "Submitting…" : "Run backtest"}
          <Icon name="arrow" size={17} />
        </button>
      </fieldset>
      {error && <ErrorMessage>{error}</ErrorMessage>}
      {success && (
        <p className="success-message" role="status">
          <Icon name="check" size={16} />
          Backtest submitted. Follow its status below.
        </p>
      )}
    </form>
  );
}

function SweepForm({
  datasets,
  onSubmitted,
}: {
  datasets: Dataset[];
  onSubmitted: (batch: Batch) => void;
}) {
  const [selected, setSelected] = useState<string[]>([]),
    [short, setShort] = useState("5, 10, 20"),
    [long, setLong] = useState("30, 50"),
    [capital, setCapital] = useState("10000"),
    [fee, setFee] = useState("1");
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<string | null>(null),
    [success, setSuccess] = useState(false);
  let combinations: number | null = null;
  try {
    const shorts = parseWindows(short),
      longs = parseWindows(long, 2);
    combinations =
      selected.length *
      shorts.reduce(
        (sum, window) => sum + longs.filter((other) => window < other).length,
        0,
      );
  } catch {
    /* Validation is shown on submission while the user edits. */
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setSuccess(false);
    try {
      if (!selected.length || selected.length > 16)
        throw new Error("Select 1–16 loaded datasets.");
      const shortWindows = parseWindows(short),
        longWindows = parseWindows(long, 2);
      const count =
        selected.length *
        shortWindows.reduce(
          (sum, window) =>
            sum + longWindows.filter((other) => window < other).length,
          0,
        );
      if (!count || count > 100)
        throw new Error(
          "A sweep must generate 1–100 valid tasks. Only short windows smaller than long windows are included.",
        );
      if (!validMoney(capital, true) || !validMoney(fee))
        throw new Error(
          "Enter positive initial capital and a nonnegative fee, with at most 24 integer and 8 decimal digits.",
        );
      setBusy(true);
      const batch = await api.submitBatch({
        datasetIds: selected,
        shortWindows,
        longWindows,
        initialCapital: capital,
        transactionFee: fee,
      });
      onSubmitted(batch);
      setSuccess(true);
    } catch (failure) {
      setError(message(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section aria-labelledby="sweep-heading">
      <h3 className="sr-only" id="sweep-heading">
        Parameter sweep
      </h3>
      <form className="strategy-form" onSubmit={submit}>
        <fieldset disabled={busy}>
          <fieldset className="dataset-choices">
            <legend>
              Datasets{" "}
              <span className="muted">{selected.length}/16 selected</span>
            </legend>
            {datasets.length ? (
              datasets.map((dataset) => (
                <label className="checkbox-choice" key={dataset.id}>
                  <input
                    type="checkbox"
                    aria-label={`${dataset.symbol} dataset ${dataset.id}`}
                    checked={selected.includes(dataset.id)}
                    disabled={
                      !selected.includes(dataset.id) && selected.length >= 16
                    }
                    onChange={(event) =>
                      setSelected((previous) =>
                        event.target.checked
                          ? [...previous, dataset.id]
                          : previous.filter((id) => id !== dataset.id),
                      )
                    }
                  />
                  <span>
                    {dataset.symbol}
                    <small>
                      {dataset.startDate} — {dataset.endDate}
                    </small>
                  </span>
                </label>
              ))
            ) : (
              <p className="field-help">
                Upload a dataset to configure a sweep.
              </p>
            )}
          </fieldset>
          <div className="form-grid">
            <label>
              Short windows
              <input
                value={short}
                onChange={(event) => setShort(event.target.value)}
                required
                placeholder="5, 10, 20"
              />
            </label>
            <label>
              Long windows
              <input
                value={long}
                onChange={(event) => setLong(event.target.value)}
                required
                placeholder="30, 50"
              />
            </label>
          </div>
          <AmountFields
            prefix="sweep"
            capital={capital}
            fee={fee}
            onCapital={setCapital}
            onFee={setFee}
          />
          <div className="sweep-estimate">
            <Icon name="layers" size={16} />
            <span>
              {combinations === null
                ? "Finish entering windows"
                : `${combinations} independent ${combinations === 1 ? "backtest" : "backtests"}`}
            </span>
            <small>max. 100</small>
          </div>
          <p className="field-help">
            Comma-separated windows, 1–10,000 bars. Invalid short/long
            combinations are skipped. Capital and fees apply independently to
            each run.
          </p>
          <button
            className="button button-primary button-full"
            type="submit"
            disabled={!datasets.length}
          >
            {busy ? "Submitting…" : "Run sweep"}
            <Icon name="arrow" size={17} />
          </button>
        </fieldset>
        {error && <ErrorMessage>{error}</ErrorMessage>}
        {success && (
          <p className="success-message" role="status">
            <Icon name="check" size={16} />
            Sweep submitted. Track its tasks below.
          </p>
        )}
      </form>
    </section>
  );
}

export function RunSetup({
  datasets,
  onJob,
  onBatch,
}: {
  datasets: Dataset[];
  onJob: (job: Job) => void;
  onBatch: (batch: Batch) => void;
}) {
  const [mode, setMode] = useState<"single" | "sweep">("single");
  return (
    <section
      className="panel setup-panel"
      id="configure"
      aria-labelledby="configure-heading"
    >
      <div className="panel-heading">
        <div>
          <span className="eyebrow">02 / CONFIGURE</span>
          <h2 id="configure-heading">Define your hypothesis</h2>
        </div>
        <span className="subtle-icon">
          <Icon name="activity" />
        </span>
      </div>
      <div className="segmented-control">
        <button
          type="button"
          aria-pressed={mode === "single"}
          onClick={() => setMode("single")}
        >
          Single backtest
        </button>
        <button
          type="button"
          aria-pressed={mode === "sweep"}
          onClick={() => setMode("sweep")}
        >
          Parameter sweep
        </button>
      </div>
      {mode === "single" ? (
        <SingleForm datasets={datasets} onSubmitted={onJob} />
      ) : (
        <SweepForm datasets={datasets} onSubmitted={onBatch} />
      )}
      <div className="execution-note">
        <span className="small-dot" />
        <p>
          <strong>Long-only SMA crossover</strong>
          <br />
          Signals at close. Execution at the next available open. Whole shares;
          no short selling.
        </p>
      </div>
    </section>
  );
}
