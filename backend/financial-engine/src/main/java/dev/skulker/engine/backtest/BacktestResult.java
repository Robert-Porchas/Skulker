package dev.skulker.engine.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/** Immutable historical simulation output; returns and drawdown are decimal ratios. */
public record BacktestResult(
    StrategyConfiguration configuration,
    String symbol,
    LocalDate startDate,
    LocalDate endDate,
    BigDecimal initialCapital,
    BigDecimal finalPortfolioValue,
    double totalReturn,
    OptionalDouble annualizedReturn,
    double maximumDrawdown,
    List<Trade> trades,
    List<EquityPoint> equityCurve,
    Map<String, String> executionMetadata) {

  public BacktestResult {
    Objects.requireNonNull(configuration, "configuration must not be null");
    Objects.requireNonNull(symbol, "symbol must not be null");
    Objects.requireNonNull(startDate, "startDate must not be null");
    Objects.requireNonNull(endDate, "endDate must not be null");
    Objects.requireNonNull(initialCapital, "initialCapital must not be null");
    Objects.requireNonNull(finalPortfolioValue, "finalPortfolioValue must not be null");
    Objects.requireNonNull(annualizedReturn, "annualizedReturn must not be null");
    trades = List.copyOf(Objects.requireNonNull(trades, "trades must not be null"));
    equityCurve = List.copyOf(Objects.requireNonNull(equityCurve, "equityCurve must not be null"));
    executionMetadata =
        Map.copyOf(Objects.requireNonNull(executionMetadata, "executionMetadata must not be null"));
    if (symbol.isBlank() || !symbol.equals(symbol.strip())) {
      throw new IllegalArgumentException("symbol must be nonblank with no surrounding whitespace");
    }
    if (endDate.isBefore(startDate)
        || initialCapital.compareTo(configuration.initialCapital()) != 0) {
      throw new IllegalArgumentException(
          "result dates and initial capital must match configuration");
    }
    if (finalPortfolioValue.signum() < 0 || !Double.isFinite(totalReturn) || totalReturn < -1) {
      throw new IllegalArgumentException("final value and total return must be valid");
    }
    if (annualizedReturn.isPresent()
        && (!Double.isFinite(annualizedReturn.getAsDouble())
            || annualizedReturn.getAsDouble() < -1)) {
      throw new IllegalArgumentException("annualized return must be finite and at least -1");
    }
    if (!Double.isFinite(maximumDrawdown) || maximumDrawdown < 0 || maximumDrawdown > 1) {
      throw new IllegalArgumentException(
          "maximumDrawdown must be a finite loss magnitude from 0 to 1");
    }
    if (equityCurve.isEmpty()
        || !equityCurve.getFirst().date().equals(startDate)
        || !equityCurve.getLast().date().equals(endDate)
        || equityCurve.getLast().portfolioValue().compareTo(finalPortfolioValue) != 0) {
      throw new IllegalArgumentException(
          "equity curve must cover result dates and end at final value");
    }
    LocalDate previousEquityDate = null;
    for (EquityPoint point : equityCurve) {
      if (previousEquityDate != null && !point.date().isAfter(previousEquityDate)) {
        throw new IllegalArgumentException("equity dates must be unique and strictly increasing");
      }
      previousEquityDate = point.date();
    }
    LocalDate previousExecutionDate = null;
    for (Trade trade : trades) {
      if (trade.signalDate().isBefore(startDate)
          || trade.executionDate().isAfter(endDate)
          || (previousExecutionDate != null
              && !trade.executionDate().isAfter(previousExecutionDate))) {
        throw new IllegalArgumentException(
            "trades must be chronological and inside the result date range");
      }
      previousExecutionDate = trade.executionDate();
    }
  }
}
