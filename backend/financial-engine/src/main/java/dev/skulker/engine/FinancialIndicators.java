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
        BigDecimal previous = bars.get(index - 1).close();
        BigDecimal change = bars.get(index).close().subtract(previous);
        result.add(
            OptionalDouble.of(toFiniteDouble(change.divide(previous, MathContext.DECIMAL128))));
      }
    }
    return List.copyOf(result);
  }

  private static double toFiniteDouble(BigDecimal value) {
    double result = value.doubleValue();
    if (!Double.isFinite(result)) {
      throw new IllegalArgumentException("indicator value exceeds the finite double range");
    }
    return result;
  }
}
