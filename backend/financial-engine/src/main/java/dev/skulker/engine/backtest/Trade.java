package dev.skulker.engine.backtest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** An executed order; an unfilled signal does not produce a trade or incur a fee. */
public record Trade(
    LocalDate signalDate,
    LocalDate executionDate,
    Side side,
    long quantity,
    BigDecimal price,
    BigDecimal fee,
    BigDecimal cashAfter) {

  public enum Side {
    BUY,
    SELL
  }

  public Trade {
    Objects.requireNonNull(signalDate, "signalDate must not be null");
    Objects.requireNonNull(executionDate, "executionDate must not be null");
    Objects.requireNonNull(side, "side must not be null");
    Objects.requireNonNull(price, "price must not be null");
    Objects.requireNonNull(fee, "fee must not be null");
    Objects.requireNonNull(cashAfter, "cashAfter must not be null");
    if (!executionDate.isAfter(signalDate)) {
      throw new IllegalArgumentException("executionDate must be after signalDate");
    }
    if (quantity <= 0 || price.signum() <= 0 || fee.signum() < 0 || cashAfter.signum() < 0) {
      throw new IllegalArgumentException(
          "trade quantity/price must be positive and fee/cash nonnegative");
    }
  }
}
