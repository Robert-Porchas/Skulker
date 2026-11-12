package dev.skulker.engine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Stateless indicators over raw closing prices, with outputs aligned to the input bars. Decimal
 * divisions use DECIMAL128 precision before approximate double conversion. Values outside the
 * finite double range are rejected; values below double precision may round to zero.
 */
public final class FinancialIndicators {
  private FinancialIndicators() {}

  /**
   * Mean of the last {@code window} closes, including the current bar. The first window - 1
   * observations are unavailable; a window larger than the series yields only unavailable values.
   */
  public static List<OptionalDouble> simpleMovingAverage(HistoricalPriceSeries series, int window) {
    Objects.requireNonNull(series, "series must not be null");
    if (window <= 0) {
      throw new IllegalArgumentException("window must be positive");
    }
    List<PriceBar> bars = series.bars();
    List<OptionalDouble> result = new ArrayList<>(bars.size());
    BigDecimal sum = BigDecimal.ZERO;
    BigDecimal divisor = BigDecimal.valueOf(window);
    for (int index = 0; index < bars.size(); index++) {
      sum = sum.add(bars.get(index).close());
      if (index >= window) {
        sum = sum.subtract(bars.get(index - window).close());
      }
      result.add(
          index < window - 1
              ? OptionalDouble.empty()
              : OptionalDouble.of(toFiniteDouble(sum.divide(divisor, MathContext.DECIMAL128))));
    }
    return List.copyOf(result);
  }

  /**
   * Simple close-to-close returns: current / previous - 1. The first observation is unavailable.
   * Gaps in dates use the preceding supplied bar; absent dates are never synthesized.
   */
  public static List<OptionalDouble> dailyReturns(HistoricalPriceSeries series) {
    Objects.requireNonNull(series, "series must not be null");
    List<PriceBar> bars = series.bars();
    List<OptionalDouble> result = new ArrayList<>(bars.size());
    for (int index = 0; index < bars.size(); index++) {
      if (index == 0) {
        result.add(OptionalDouble.empty());
      } else {
        result.add(
            OptionalDouble.of(
                toFiniteDouble(
                    simpleReturn(bars.get(index).close(), bars.get(index - 1).close()))));
      }
    }
    return List.copyOf(result);
  }

  /**
   * Raw-close return relative to the first supplied close. The initial value is available and zero;
   * empty input yields an empty result. Calendar gaps are not filled or normalized.
   */
  public static List<OptionalDouble> cumulativeReturns(HistoricalPriceSeries series) {
    Objects.requireNonNull(series, "series must not be null");
    List<PriceBar> bars = series.bars();
    if (bars.isEmpty()) {
      return List.of();
    }
    BigDecimal initial = bars.getFirst().close();
    List<OptionalDouble> result = new ArrayList<>(bars.size());
    for (PriceBar bar : bars) {
      result.add(OptionalDouble.of(toFiniteDouble(simpleReturn(bar.close(), initial))));
    }
    return List.copyOf(result);
  }

  /**
   * Unannualized sample standard deviation over the last {@code window} daily simple returns, using
   * denominator {@code window - 1}. Requires a window of at least two returns; the first available
   * result is at bar index {@code window}. Two-pass decimal arithmetic avoids cancellation in a
   * squared-sums formula. This implementation takes O(number of bars * window) time.
   */
  public static List<OptionalDouble> rollingVolatility(HistoricalPriceSeries series, int window) {
    Objects.requireNonNull(series, "series must not be null");
    if (window < 2) {
      throw new IllegalArgumentException("volatility window must be at least two returns");
    }
    List<PriceBar> bars = series.bars();
    List<BigDecimal> returns = new ArrayList<>();
    for (int index = 1; index < bars.size(); index++) {
      returns.add(simpleReturn(bars.get(index).close(), bars.get(index - 1).close()));
    }
    List<OptionalDouble> result = new ArrayList<>(bars.size());
    BigDecimal count = BigDecimal.valueOf(window);
    BigDecimal denominator = BigDecimal.valueOf(window - 1L);
    for (int index = 0; index < bars.size(); index++) {
      if (index < window) {
        result.add(OptionalDouble.empty());
        continue;
      }
      BigDecimal sum = BigDecimal.ZERO;
      for (int returnIndex = index - window; returnIndex < index; returnIndex++) {
        sum = sum.add(returns.get(returnIndex));
      }
      BigDecimal mean = sum.divide(count, MathContext.DECIMAL128);
      BigDecimal squaredDeviations = BigDecimal.ZERO;
      for (int returnIndex = index - window; returnIndex < index; returnIndex++) {
        BigDecimal deviation = returns.get(returnIndex).subtract(mean);
        squaredDeviations = squaredDeviations.add(deviation.multiply(deviation));
      }
      BigDecimal variance = squaredDeviations.divide(denominator, MathContext.DECIMAL128);
      result.add(OptionalDouble.of(toFiniteDouble(variance.sqrt(MathContext.DECIMAL128))));
    }
    return List.copyOf(result);
  }

  /**
   * Largest peak-to-trough loss as a nonnegative fraction: {@code max(1 - equity / runningPeak)}.
   * Empty input is unavailable. The first equity value must be positive; later values may be zero
   * for a total loss but must not be negative. No terminal liquidation is inferred.
   */
  public static OptionalDouble maximumDrawdown(List<BigDecimal> equity) {
    Objects.requireNonNull(equity, "equity must not be null");
    if (equity.isEmpty()) {
      return OptionalDouble.empty();
    }
    BigDecimal peak = Objects.requireNonNull(equity.getFirst(), "equity values must not be null");
    if (peak.signum() <= 0) {
      throw new IllegalArgumentException("initial equity must be positive");
    }
    BigDecimal maximum = BigDecimal.ZERO;
    for (BigDecimal value : equity) {
      Objects.requireNonNull(value, "equity values must not be null");
      if (value.signum() < 0) {
        throw new IllegalArgumentException("equity values must not be negative");
      }
      peak = peak.max(value);
      BigDecimal drawdown = peak.subtract(value).divide(peak, MathContext.DECIMAL128);
      maximum = maximum.max(drawdown);
    }
    return OptionalDouble.of(toFiniteDouble(maximum));
  }

  private static BigDecimal simpleReturn(BigDecimal current, BigDecimal previous) {
    return current.subtract(previous).divide(previous, MathContext.DECIMAL128);
  }

  private static double toFiniteDouble(BigDecimal value) {
    double result = value.doubleValue();
    if (!Double.isFinite(result)) {
      throw new IllegalArgumentException("indicator value exceeds the finite double range");
    }
    return result;
  }
}
