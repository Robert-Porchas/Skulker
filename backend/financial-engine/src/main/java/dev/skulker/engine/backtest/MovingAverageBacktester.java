package dev.skulker.engine.backtest;

import dev.skulker.engine.HistoricalPriceSeries;
import dev.skulker.engine.PriceBar;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Deterministic, long-only SMA crossover simulation. Signals use completed raw closes and execute
 * once at the next supplied bar's open. Orders use whole shares and exact decimal cash accounting.
 * Unaffordable orders are skipped without fees; positions remain marked to close at dataset end.
 */
public final class MovingAverageBacktester {
  private static final BigDecimal ZERO = BigDecimal.ZERO;
  private static final Map<String, String> METADATA =
      Map.ofEntries(
          Map.entry("engineVersion", "1"),
          Map.entry("strategy", "SMA_CROSSOVER"),
          Map.entry("signalPrice", "RAW_CLOSE"),
          Map.entry("executionPrice", "NEXT_AVAILABLE_OPEN"),
          Map.entry("sharePolicy", "WHOLE_SHARES_ALL_AFFORDABLE"),
          Map.entry("feePolicy", "FIXED_PER_EXECUTED_TRADE"),
          Map.entry("unaffordableOrderPolicy", "SKIP_WITHOUT_FEE"),
          Map.entry("terminalPositionPolicy", "MARK_TO_LAST_CLOSE"),
          Map.entry("annualization", "ACT/365.25"),
          Map.entry("signalArithmetic", "EXACT_DECIMAL_SMA_COMPARISON"));

  public BacktestResult run(HistoricalPriceSeries series, StrategyConfiguration configuration) {
    Objects.requireNonNull(series, "series must not be null");
    Objects.requireNonNull(configuration, "configuration must not be null");
    List<PriceBar> bars = series.bars();
    if (bars.isEmpty()) {
      throw new IllegalArgumentException("backtesting requires at least one price bar");
    }
    BigDecimal cash = configuration.initialCapital();
    long shares = 0;
    BigDecimal shortSum = ZERO;
    BigDecimal longSum = ZERO;
    Integer previousComparison = null;
    Trade.Side pendingSide = null;
    LocalDate signalDate = null;
    List<Trade> trades = new ArrayList<>();
    List<EquityPoint> equityCurve = new ArrayList<>(bars.size());
    BigDecimal peak = cash;
    double maximumDrawdown = 0;

    for (int index = 0; index < bars.size(); index++) {
      PriceBar bar = bars.get(index);
      if (pendingSide == Trade.Side.BUY && shares == 0) {
        BigDecimal availableCash = cash.subtract(configuration.transactionFee());
        if (availableCash.compareTo(bar.open()) >= 0) {
          long quantity;
          try {
            quantity = availableCash.divide(bar.open(), 0, RoundingMode.FLOOR).longValueExact();
          } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                "affordable share quantity exceeds long range", exception);
          }
          cash =
              cash.subtract(bar.open().multiply(BigDecimal.valueOf(quantity)))
                  .subtract(configuration.transactionFee());
          shares = quantity;
          trades.add(
              new Trade(
                  signalDate,
                  bar.date(),
                  Trade.Side.BUY,
                  quantity,
                  bar.open(),
                  configuration.transactionFee(),
                  cash));
        }
      } else if (pendingSide == Trade.Side.SELL && shares > 0) {
        BigDecimal cashAfter =
            cash.add(bar.open().multiply(BigDecimal.valueOf(shares)))
                .subtract(configuration.transactionFee());
        if (cashAfter.signum() >= 0) {
          cash = cashAfter;
          trades.add(
              new Trade(
                  signalDate,
                  bar.date(),
                  Trade.Side.SELL,
                  shares,
                  bar.open(),
                  configuration.transactionFee(),
                  cash));
          shares = 0;
        }
      }
      pendingSide = null;
      signalDate = null;

      BigDecimal value = cash.add(bar.close().multiply(BigDecimal.valueOf(shares)));
      equityCurve.add(new EquityPoint(bar.date(), cash, shares, bar.close(), value));
      peak = peak.max(value);
      double drawdown = finiteRatio(peak.subtract(value), peak);
      maximumDrawdown = Math.max(maximumDrawdown, drawdown);

      shortSum = shortSum.add(bar.close());
      longSum = longSum.add(bar.close());
      if (index >= configuration.shortWindow()) {
        shortSum = shortSum.subtract(bars.get(index - configuration.shortWindow()).close());
      }
      if (index >= configuration.longWindow()) {
        longSum = longSum.subtract(bars.get(index - configuration.longWindow()).close());
      }
      if (index >= configuration.longWindow() - 1) {
        // Compare means by cross-multiplying exact sums, without rounded indicator conversions.
        int comparison =
            shortSum
                .multiply(BigDecimal.valueOf(configuration.longWindow()))
                .compareTo(longSum.multiply(BigDecimal.valueOf(configuration.shortWindow())));
        if (previousComparison != null) {
          if (previousComparison <= 0 && comparison > 0 && shares == 0) {
            pendingSide = Trade.Side.BUY;
          } else if (previousComparison >= 0 && comparison < 0 && shares > 0) {
            pendingSide = Trade.Side.SELL;
          }
          if (pendingSide != null) {
            signalDate = bar.date();
          }
        }
        previousComparison = comparison;
      }
    }

    BigDecimal finalValue = equityCurve.getLast().portfolioValue();
    double totalReturn =
        finiteRatio(
            finalValue.subtract(configuration.initialCapital()), configuration.initialCapital());
    LocalDate startDate = bars.getFirst().date();
    LocalDate endDate = bars.getLast().date();
    long elapsedDays = ChronoUnit.DAYS.between(startDate, endDate);
    OptionalDouble annualizedReturn = OptionalDouble.empty();
    if (elapsedDays > 0) {
      double annualized =
          finalValue.signum() == 0
              ? -1
              : StrictMath.expm1(
                  logarithmicGrossReturn(finalValue, configuration.initialCapital(), totalReturn)
                      * (365.25 / elapsedDays));
      if (!Double.isFinite(annualized)) {
        throw new IllegalArgumentException("annualized return exceeds finite double range");
      }
      annualizedReturn = OptionalDouble.of(annualized);
    }
    return new BacktestResult(
        configuration,
        series.symbol(),
        startDate,
        endDate,
        configuration.initialCapital(),
        finalValue,
        totalReturn,
        annualizedReturn,
        maximumDrawdown,
        trades,
        equityCurve,
        METADATA);
  }

  private static double finiteRatio(BigDecimal numerator, BigDecimal denominator) {
    double ratio = numerator.divide(denominator, MathContext.DECIMAL128).doubleValue();
    if (!Double.isFinite(ratio)) {
      throw new IllegalArgumentException("performance metric exceeds finite double range");
    }
    return ratio;
  }

  private static double logarithmicGrossReturn(
      BigDecimal finalValue, BigDecimal initialCapital, double totalReturn) {
    if (totalReturn >= -0.5) {
      return StrictMath.log1p(totalReturn);
    }
    // Subtracting one loses relative precision for large losses, even before a net
    // return rounds to -1. Preserve the gross decimal magnitude for these cases.
    BigDecimal grossReturn = finalValue.divide(initialCapital, MathContext.DECIMAL128);
    long exponent = (long) grossReturn.precision() - grossReturn.scale() - 1;
    BigDecimal significand =
        new BigDecimal(grossReturn.unscaledValue(), grossReturn.precision() - 1);
    return StrictMath.log(significand.doubleValue()) + exponent * StrictMath.log(10);
  }
}
