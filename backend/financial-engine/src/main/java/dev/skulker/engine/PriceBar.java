package dev.skulker.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** A completed daily OHLCV observation; all prices are strictly positive. */
public record PriceBar(
    String symbol,
    LocalDate date,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume,
    Optional<BigDecimal> adjustedClose) {

  public PriceBar {
    requireSymbol(symbol);
    Objects.requireNonNull(date, "date must not be null");
    requirePositive(open, "open");
    requirePositive(high, "high");
    requirePositive(low, "low");
    requirePositive(close, "close");
    if (low.compareTo(high) > 0
        || open.compareTo(low) < 0
        || open.compareTo(high) > 0
        || close.compareTo(low) < 0
        || close.compareTo(high) > 0) {
      throw new IllegalArgumentException("open and close must lie between low and high");
    }
    if (volume < 0) {
      throw new IllegalArgumentException("volume must not be negative");
    }
    Objects.requireNonNull(adjustedClose, "adjustedClose must not be null");
    adjustedClose.ifPresent(value -> requirePositive(value, "adjustedClose"));
  }

  public PriceBar(
      String symbol,
      LocalDate date,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      long volume) {
    this(symbol, date, open, high, low, close, volume, Optional.empty());
  }

  static void requireSymbol(String symbol) {
    Objects.requireNonNull(symbol, "symbol must not be null");
    if (symbol.isBlank() || !symbol.equals(symbol.strip())) {
      throw new IllegalArgumentException("symbol must be nonblank with no surrounding whitespace");
    }
  }

  private static void requirePositive(BigDecimal price, String name) {
    Objects.requireNonNull(price, name + " must not be null");
    if (price.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
