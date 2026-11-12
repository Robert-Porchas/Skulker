package dev.skulker.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class HistoricalPriceSeriesTest {
  @Test
  void emptySeriesRetainsExplicitSymbol() {
    HistoricalPriceSeries series = new HistoricalPriceSeries("TEST", List.of());

    assertEquals("TEST", series.symbol());
    assertEquals(List.of(), series.bars());
  }

  @Test
  void preservesChronologicalBarsAndCalendarGaps() {
    List<PriceBar> bars = List.of(bar("TEST", 5), bar("TEST", 8));

    assertEquals(bars, new HistoricalPriceSeries("TEST", bars).bars());
  }

  @Test
  void defensivelyCopiesInputAndExposesUnmodifiableBars() {
    PriceBar first = bar("TEST", 2);
    ArrayList<PriceBar> input = new ArrayList<>(List.of(first));
    HistoricalPriceSeries series = new HistoricalPriceSeries("TEST", input);
    input.add(bar("TEST", 3));
    input.clear();

    assertEquals(List.of(first), series.bars());
    assertThrows(UnsupportedOperationException.class, () -> series.bars().clear());
  }

  @Test
  void rejectsDuplicateTradingDates() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HistoricalPriceSeries("TEST", List.of(bar("TEST", 2), bar("TEST", 2))));
  }

  @Test
  void rejectsUnsortedInputRatherThanSilentlySorting() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HistoricalPriceSeries("TEST", List.of(bar("TEST", 3), bar("TEST", 2))));
  }

  @Test
  void rejectsBarsWithDifferentSymbols() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HistoricalPriceSeries("TEST", List.of(bar("TEST", 2), bar("OTHER", 3))));
  }

  @Test
  void comparesSymbolIdentityCaseSensitively() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new HistoricalPriceSeries("test", List.of(bar("TEST", 2))));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", " TEST", "TEST "})
  void rejectsInvalidSymbolEvenWithoutBars(String symbol) {
    Class<? extends RuntimeException> expected =
        symbol == null ? NullPointerException.class : IllegalArgumentException.class;
    assertThrows(expected, () -> new HistoricalPriceSeries(symbol, List.of()));
  }

  @Test
  void rejectsMissingBars() {
    assertThrows(NullPointerException.class, () -> new HistoricalPriceSeries("TEST", null));
  }

  @Test
  void rejectsNullObservations() {
    assertThrows(
        NullPointerException.class,
        () -> new HistoricalPriceSeries("TEST", Arrays.asList(bar("TEST", 2), null)));
  }

  private static PriceBar bar(String symbol, int day) {
    return new PriceBar(
        symbol,
        LocalDate.of(2024, 1, day),
        BigDecimal.TEN,
        BigDecimal.TEN,
        BigDecimal.TEN,
        BigDecimal.TEN,
        0);
  }
}
