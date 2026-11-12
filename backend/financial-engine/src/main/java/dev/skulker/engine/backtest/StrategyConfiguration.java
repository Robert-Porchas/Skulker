package dev.skulker.engine.backtest;

import java.math.BigDecimal;
import java.util.Objects;

/** Whole-share, long-only SMA crossover parameters with a fixed fee per executed trade. */
public record StrategyConfiguration(
    int shortWindow, int longWindow, BigDecimal initialCapital, BigDecimal transactionFee) {

  public StrategyConfiguration {
    if (shortWindow <= 0 || longWindow <= shortWindow) {
      throw new IllegalArgumentException("windows must satisfy 0 < shortWindow < longWindow");
    }
    Objects.requireNonNull(initialCapital, "initialCapital must not be null");
    Objects.requireNonNull(transactionFee, "transactionFee must not be null");
    if (initialCapital.signum() <= 0) {
      throw new IllegalArgumentException("initialCapital must be positive");
    }
    if (transactionFee.signum() < 0) {
      throw new IllegalArgumentException("transactionFee must not be negative");
    }
  }

  public StrategyType strategyType() {
    return StrategyType.SMA_CROSSOVER;
  }
}
