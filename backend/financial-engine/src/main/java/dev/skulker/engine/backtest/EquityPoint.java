package dev.skulker.engine.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** End-of-bar portfolio value, marking any open position at the raw closing price. */
public record EquityPoint(
    LocalDate date, BigDecimal cash, long shares, BigDecimal close, BigDecimal portfolioValue) {

  public EquityPoint {
    Objects.requireNonNull(date, "date must not be null");
    Objects.requireNonNull(cash, "cash must not be null");
    Objects.requireNonNull(close, "close must not be null");
    Objects.requireNonNull(portfolioValue, "portfolioValue must not be null");
    if (cash.signum() < 0 || shares < 0 || close.signum() <= 0 || portfolioValue.signum() < 0) {
      throw new IllegalArgumentException(
          "cash, shares, and value must be nonnegative; close positive");
    }
    BigDecimal expectedValue = cash.add(close.multiply(BigDecimal.valueOf(shares)));
    if (portfolioValue.compareTo(expectedValue) != 0) {
      throw new IllegalArgumentException("portfolioValue must equal cash plus shares times close");
    }
  }
}
