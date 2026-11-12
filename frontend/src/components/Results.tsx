"use client";

import { useState } from "react";
import { formatDecimal, formatPercent } from "@/lib/format";
import type { BacktestResult } from "@/lib/types";
import EquityChart from "./EquityChart";
import { EmptyState, ErrorMessage, Icon, shortId } from "./Primitives";

export interface ComparedResult {
  id: string;
  result: BacktestResult;
}

export function ResultsPanel({
  selected,
  result,
  loading,
  error,
  onRetry,
}: {
  selected: string | null;
  result: BacktestResult | null;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
}) {
  const [tradePage, setTradePage] = useState(0);
  const tradeOffset = Math.min(
    tradePage * 20,
    Math.max(0, (Math.ceil((result?.trades.length ?? 0) / 20) - 1) * 20),
  );
  return (
    <section
      className="panel results-panel"
      id="results"
      aria-labelledby="results-heading"
      tabIndex={-1}
      aria-busy={loading}
    >
      <div className="panel-heading">
        <div>
          <span className="eyebrow">04 / ANALYZE</span>
          <h2 id="results-heading">Results</h2>
        </div>
        {result && (
          <span className="result-symbol">
            {result.symbol}
            <span>
              SMA {result.configuration.shortWindow}/
              {result.configuration.longWindow}
            </span>
          </span>
        )}
      </div>
      {loading ? (
        <div className="loading-state" role="status">
          <span className="spinner" />
          Loading the recorded backtest…
        </div>
      ) : error ? (
        <div className="result-error">
          <ErrorMessage>{error}</ErrorMessage>
          <button className="button button-outline" onClick={onRetry}>
            Retry result
          </button>
        </div>
      ) : !result ? (
        <EmptyState icon="chart" title="Give your research a curve.">
          Choose View result on a succeeded backtest to explore its equity,
          trades, and assumptions.
        </EmptyState>
      ) : (
        <>
          <div className="result-context">
            <span>
              <time>{result.startDate}</time> — <time>{result.endDate}</time>
            </span>
            <span>
              Initial capital{" "}
              <strong>{formatDecimal(result.initialCapital)}</strong>
            </span>
            <span>
              Fee / trade{" "}
              <strong>
                {formatDecimal(result.configuration.transactionFee)}
              </strong>
            </span>
            <code title={selected ?? ""}>
              run {selected ? shortId(selected) : ""}
            </code>
          </div>
          <dl className="result-metrics">
            <div>
              <dt>Final portfolio value</dt>
              <dd className="monetary" title={result.finalPortfolioValue}>
                {formatDecimal(result.finalPortfolioValue)}
              </dd>
            </div>
            <div>
              <dt>Total return</dt>
              <dd className={result.totalReturn < 0 ? "negative" : "positive"}>
                {formatPercent(result.totalReturn)}
              </dd>
            </div>
            <div>
              <dt>Annualized return</dt>
              <dd>{formatPercent(result.annualizedReturn)}</dd>
              {result.annualizedReturn === null && (
                <small>Needs elapsed calendar time.</small>
              )}
            </div>
            <div>
              <dt>Maximum drawdown</dt>
              <dd>{formatPercent(result.maximumDrawdown)}</dd>
              <small>Peak-to-trough loss</small>
            </div>
          </dl>
          <div className="chart-heading">
            <h3>Equity curve</h3>
            <span>
              {result.equityCurve.length.toLocaleString()} daily observations
            </span>
          </div>
          <EquityChart
            key={selected}
            series={[
              {
                id: selected ?? "result",
                label: `${result.symbol} · SMA ${result.configuration.shortWindow}/${result.configuration.longWindow}`,
                result,
              },
            ]}
          />
          <div className="table-scroll">
            <table className="trade-table">
              <caption>
                Trade history{" "}
                <span>
                  {result.trades.length.toLocaleString()} executed trades
                </span>
              </caption>
              <thead>
                <tr>
                  <th>Side</th>
                  <th>Signal date</th>
                  <th>Execution date</th>
                  <th className="numeric">Shares</th>
                  <th className="numeric">Price</th>
                  <th className="numeric">Fee</th>
                  <th className="numeric">Cash after</th>
                </tr>
              </thead>
              <tbody>
                {result.trades
                  .slice(tradeOffset, tradeOffset + 20)
                  .map((trade) => (
                    <tr key={`${trade.executionDate}-${trade.side}`}>
                      <td>
                        <span
                          className={`trade-side ${trade.side.toLowerCase()}`}
                        >
                          {trade.side}
                        </span>
                      </td>
                      <td>{trade.signalDate}</td>
                      <td>{trade.executionDate}</td>
                      <td className="numeric">
                        {formatDecimal(trade.quantity)}
                      </td>
                      <td className="numeric">{formatDecimal(trade.price)}</td>
                      <td className="numeric">{formatDecimal(trade.fee)}</td>
                      <td className="numeric">
                        {formatDecimal(trade.cashAfter)}
                      </td>
                    </tr>
                  ))}
                {!result.trades.length && (
                  <tr>
                    <td colSpan={7} className="table-empty">
                      No executed trades. The data may contain no eligible
                      crossover after warmup, or no affordable next-open order.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          {result.trades.length > 20 && (
            <nav className="pagination" aria-label="Trade history pagination">
              <span>
                {tradeOffset + 1}–
                {Math.min(tradeOffset + 20, result.trades.length)} of{" "}
                {result.trades.length}
              </span>
              <div>
                <button
                  className="button button-small button-quiet"
                  disabled={tradeOffset === 0}
                  onClick={() => setTradePage(Math.max(0, tradePage - 1))}
                >
                  Previous trades
                </button>
                <button
                  className="button button-small button-quiet"
                  disabled={tradeOffset + 20 >= result.trades.length}
                  onClick={() => setTradePage(tradePage + 1)}
                >
                  Next trades
                </button>
              </div>
            </nav>
          )}
          <p className="result-assumptions">
            <Icon name="layers" size={15} />
            No trades during SMA warmup or without a next trading day. Open
            positions remain valued at the final close; no forced liquidation.
          </p>
        </>
      )}
    </section>
  );
}

export function ComparisonPanel({
  results,
  onView,
}: {
  results: ComparedResult[];
  onView: (id: string) => void;
}) {
  if (!results.length) return null;
  return (
    <section
      className="panel comparison-panel"
      id="comparison"
      aria-labelledby="comparison-heading"
      tabIndex={-1}
    >
      <div className="panel-heading">
        <div>
          <span className="eyebrow">SIDE BY SIDE</span>
          <h2 id="comparison-heading">Compare your hypotheses</h2>
        </div>
        <span className="count-pill">{results.length} runs</span>
      </div>
      <EquityChart
        series={results.map(({ id, result }) => ({
          id,
          label: `${result.symbol} · SMA ${result.configuration.shortWindow}/${result.configuration.longWindow}`,
          result,
        }))}
      />
      <div className="table-scroll">
        <table>
          <caption>Strategy comparison</caption>
          <thead>
            <tr>
              <th>Run / symbol</th>
              <th>Strategy</th>
              <th>Dataset period</th>
              <th className="numeric">Initial capital</th>
              <th className="numeric">Fee / trade</th>
              <th className="numeric">Final value</th>
              <th className="numeric">Total return</th>
              <th className="numeric">Max. drawdown</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {results.map(({ id, result }) => (
              <tr key={id}>
                <td>
                  <strong>{result.symbol}</strong>
                  <small className="cell-subtext">{shortId(id)}</small>
                </td>
                <td>
                  SMA {result.configuration.shortWindow}/
                  {result.configuration.longWindow}
                </td>
                <td className="date-cell">
                  {result.startDate}
                  <span>to {result.endDate}</span>
                </td>
                <td className="numeric">
                  {formatDecimal(result.initialCapital)}
                </td>
                <td className="numeric">
                  {formatDecimal(result.configuration.transactionFee)}
                </td>
                <td className="numeric">
                  {formatDecimal(result.finalPortfolioValue)}
                </td>
                <td
                  className={`numeric ${result.totalReturn < 0 ? "negative" : "positive"}`}
                >
                  {formatPercent(result.totalReturn)}
                </td>
                <td className="numeric">
                  {formatPercent(result.maximumDrawdown)}
                </td>
                <td>
                  <button
                    className="button button-small button-quiet"
                    onClick={() => onView(id)}
                    aria-label={`Inspect compared job ${id}`}
                  >
                    Inspect
                    <Icon name="arrow" size={14} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="result-assumptions">
        Compare the date range, capital, and fee assumptions before interpreting
        differences. Historical simulations are research, not forecasts.
      </p>
    </section>
  );
}
