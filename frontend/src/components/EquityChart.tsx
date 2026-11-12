"use client";

import { useId, useMemo, useState } from "react";
import { formatDecimal } from "@/lib/format";
import type { BacktestResult } from "@/lib/types";

export interface ChartSeries {
  id: string;
  label: string;
  result: BacktestResult;
}
const COLORS = ["#b5e9ad", "#63c9c1", "#e8bd80", "#b5a1e9"];

/** Only a truncated dimensionless ratio crosses into Number; source money stays decimal text. */
function normalized(value: string, initial: string): number {
  function parts(decimal: string): [bigint, bigint] {
    const match = /^([+-]?)(\d+)(?:\.(\d*))?(?:[eE]([+-]?\d+))?$/.exec(decimal);
    if (!match)
      throw new Error(
        "Equity cannot be plotted from this decimal representation.",
      );
    const fraction = match[3] ?? "";
    const exponent = Number(match[4] ?? "0") - fraction.length;
    if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 1000)
      throw new Error("Equity is outside the chart's visual range.");
    const digits = BigInt((match[1] === "-" ? "-" : "") + match[2] + fraction);
    return exponent >= 0
      ? [digits * 10n ** BigInt(exponent), 1n]
      : [digits, 10n ** BigInt(-exponent)];
  }
  const [numerator, denominator] = parts(value);
  const [capital, capitalDenominator] = parts(initial);
  if (capital <= 0n)
    throw new Error(
      "A positive initial capital is required for normalization.",
    );
  const index =
    Number(
      (numerator * capitalDenominator * 100_000_000n) / (denominator * capital),
    ) / 1_000_000;
  if (!Number.isFinite(index))
    throw new Error("Equity is outside the chart's visual range.");
  return index;
}

export default function EquityChart({ series }: { series: ChartSeries[] }) {
  const titleId = useId();
  const descriptionId = useId();
  const [inspected, setInspected] = useState(0);
  const chart = useMemo(() => {
    try {
      const curves = series.map((item, color) => ({
        ...item,
        color: COLORS[color % COLORS.length],
        points: item.result.equityCurve.map((point) => ({
          ...point,
          at: Date.parse(point.date),
          index: normalized(point.portfolioValue, item.result.initialCapital),
        })),
      }));
      const all = curves.flatMap((curve) => curve.points);
      if (!all.length)
        return {
          error: "No equity observations are available.",
          curves: [],
          minimum: 0,
          maximum: 100,
          first: 0,
          last: 1,
        };
      let minimum = 100,
        maximum = 100,
        first = all[0].at,
        last = all[0].at;
      for (const point of all) {
        minimum = Math.min(minimum, point.index);
        maximum = Math.max(maximum, point.index);
        first = Math.min(first, point.at);
        last = Math.max(last, point.at);
      }
      const padding = Math.max((maximum - minimum) * 0.15, 2);
      return {
        curves,
        minimum: Math.max(0, minimum - padding),
        maximum: maximum + padding,
        first,
        last,
        error: null,
      };
    } catch (error) {
      return {
        error:
          error instanceof Error
            ? error.message
            : "Equity could not be plotted.",
        curves: [],
        minimum: 0,
        maximum: 100,
        first: 0,
        last: 1,
      };
    }
  }, [series]);
  const width = 900,
    height = 260,
    left = 58,
    right = 20,
    top = 18,
    bottom = 38;
  const x = (at: number) =>
    left +
    ((at - chart.first) / (chart.last - chart.first || 1)) *
      (width - left - right);
  const y = (value: number) =>
    top +
    ((chart.maximum - value) / (chart.maximum - chart.minimum || 1)) *
      (height - top - bottom);
  const firstCurve = chart.curves[0];
  const point =
    firstCurve?.points[Math.min(inspected, firstCurve.points.length - 1)];
  function date(at: number) {
    return new Date(at).toISOString().slice(0, 10);
  }

  if (chart.error)
    return (
      <p className="empty-inline" role="status">
        {chart.error} Exact values remain available in the results.
      </p>
    );
  return (
    <div className="equity-chart">
      <div className="chart-legend">
        {chart.curves.map((curve) => (
          <span key={curve.id}>
            <i style={{ background: curve.color }} />
            {curve.label}
          </span>
        ))}
        <span className="chart-basis">Initial capital = 100</span>
      </div>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-labelledby={`${titleId} ${descriptionId}`}
      >
        <title id={titleId}>Normalized equity curve</title>
        <desc id={descriptionId}>
          Portfolio value relative to each run&apos;s initial capital, indexed
          to 100. Dates share a calendar axis. Lines sample at most 400
          observations per run; the point inspector retains all observations.
        </desc>
        {[0, 0.25, 0.5, 0.75, 1].map((fraction) => {
          const value =
            chart.minimum + fraction * (chart.maximum - chart.minimum);
          return (
            <g key={fraction}>
              <line
                x1={left}
                y1={y(value)}
                x2={width - right}
                y2={y(value)}
                className="chart-grid"
              />
              <text
                x={left - 12}
                y={y(value) + 4}
                textAnchor="end"
                className="chart-axis"
              >
                {new Intl.NumberFormat("en-US", {
                  maximumFractionDigits: 1,
                  notation: value > 1e6 ? "compact" : "standard",
                }).format(value)}
              </text>
            </g>
          );
        })}
        <line
          x1={left}
          y1={y(100)}
          x2={width - right}
          y2={y(100)}
          className="chart-baseline"
        />
        {chart.curves.map((curve) => {
          const sampled = curve.points.filter(
            (_, index) =>
              index % Math.max(1, Math.ceil(curve.points.length / 399)) === 0 ||
              index === curve.points.length - 1,
          );
          return (
            <path
              key={curve.id}
              d={sampled
                .map(
                  (item, index) =>
                    `${index ? "L" : "M"}${x(item.at).toFixed(2)},${y(item.index).toFixed(2)}`,
                )
                .join(" ")}
              fill="none"
              stroke={curve.color}
              strokeWidth="2.6"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          );
        })}
        {point && (
          <>
            <line
              x1={x(point.at)}
              y1={top}
              x2={x(point.at)}
              y2={height - bottom}
              className="chart-cursor"
            />
            <circle
              cx={x(point.at)}
              cy={y(point.index)}
              r="4"
              fill={COLORS[0]}
              stroke="#141a19"
              strokeWidth="2"
            />
          </>
        )}
        <text x={left} y={height - 10} className="chart-axis">
          {date(chart.first)}
        </text>
        <text
          x={width - right}
          y={height - 10}
          textAnchor="end"
          className="chart-axis"
        >
          {date(chart.last)}
        </text>
      </svg>
      {point && (
        <div className="chart-inspector">
          <label>
            Inspect equity point
            <input
              aria-label="Inspect equity point"
              type="range"
              min="0"
              max={firstCurve.points.length - 1}
              value={Math.min(inspected, firstCurve.points.length - 1)}
              onChange={(event) => setInspected(Number(event.target.value))}
            />
          </label>
          <output aria-live="polite">
            <time>{point.date}</time>
            <span>
              Portfolio <strong>{formatDecimal(point.portfolioValue)}</strong>
            </span>
            <span>
              Shares <strong>{formatDecimal(point.shares)}</strong>
            </span>
          </output>
        </div>
      )}
      <p className="chart-note">
        Normalized growth, not a currency conversion. Lines show at most 400
        sampled points per run and may omit short-lived peaks or dips.{" "}
        {series.length > 1
          ? "Different periods and assumptions are not a controlled comparison. The inspector follows the first run."
          : "Use the slider to inspect all original decimal portfolio values."}
      </p>
    </div>
  );
}
