package dev.skulker.engine;

import java.util.List;
import java.util.Objects;

/** Immutable observations for one symbol, in strictly increasing trading-date order. */
public record HistoricalPriceSeries(String symbol, List<PriceBar> bars) {

  public HistoricalPriceSeries {
    PriceBar.requireSymbol(symbol);
    bars = List.copyOf(Objects.requireNonNull(bars, "bars must not be null"));
    PriceBar previous = null;
    for (PriceBar bar : bars) {
      if (!symbol.equals(bar.symbol())) {
        throw new IllegalArgumentException("all bars must have series symbol " + symbol);
      }
      if (previous != null && !bar.date().isAfter(previous.date())) {
        throw new IllegalArgumentException("trading dates must be unique and strictly increasing");
      }
      previous = bar;
    }
  }
}
