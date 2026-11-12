package dev.skulker.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FinancialIndicatorsTest {
  private static final double TOLERANCE = 1e-12;

  @Test
  void movingAverageUsesOnlyCurrentAndPreviousCloses() {
    List<OptionalDouble> actual =
        FinancialIndicators.simpleMovingAverage(series("10", "20", "30", "40", "50"), 3);

    assertEquals(5, actual.size());
    assertFalse(actual.get(0).isPresent());
    assertFalse(actual.get(1).isPresent());
    assertValue(20, actual.get(2));
    assertValue(30, actual.get(3));
    assertValue(40, actual.get(4));
  }

  @Test
  void movingAverageWindowOneEqualsEachClose() {
    List<OptionalDouble> actual = FinancialIndicators.simpleMovingAverage(series("10", "20"), 1);

    assertValue(10, actual.get(0));
    assertValue(20, actual.get(1));
  }

  @Test
  void movingAverageWindowEqualToSeriesHasOneAvailableValue() {
    assertEquals(
        List.of(OptionalDouble.empty(), OptionalDouble.of(15)),
        FinancialIndicators.simpleMovingAverage(series("10", "20"), 2));
  }

  @Test
  void insufficientHistoryReturnsAlignedUnavailableValues() {
    assertEquals(
        List.of(OptionalDouble.empty(), OptionalDouble.empty()),
        FinancialIndicators.simpleMovingAverage(series("10", "20"), 3));
  }

  @Test
  void emptySeriesProducesEmptyIndicators() {
    assertEquals(List.of(), FinancialIndicators.simpleMovingAverage(series(), 2));
    assertEquals(List.of(), FinancialIndicators.dailyReturns(series()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void rejectsInvalidWindowForEmptyAndNonemptySeries(int window) {
    assertThrows(
        IllegalArgumentException.class,
        () -> FinancialIndicators.simpleMovingAverage(series(), window));
    assertThrows(
        IllegalArgumentException.class,
        () -> FinancialIndicators.simpleMovingAverage(series("10"), window));
  }

  @Test
  void veryLargeWindowIsUnavailableWithoutIntegerOverflow() {
    assertEquals(
        List.of(OptionalDouble.empty()),
        FinancialIndicators.simpleMovingAverage(series("10"), Integer.MAX_VALUE));
  }

  @Test
  void dailyReturnsFollowHandCalculatedCloseRatios() {
    List<OptionalDouble> actual = FinancialIndicators.dailyReturns(series("100", "110", "99"));

    assertEquals(3, actual.size());
    assertFalse(actual.get(0).isPresent());
    assertValue(0.1, actual.get(1));
    assertValue(-0.1, actual.get(2));
  }

  @Test
  void singleObservationHasNoDailyReturn() {
    assertEquals(List.of(OptionalDouble.empty()), FinancialIndicators.dailyReturns(series("100")));
  }

  @Test
  void constantPricesHaveZeroReturnsAndConstantMovingAverage() {
    HistoricalPriceSeries series = series("12.5", "12.5", "12.5");

    assertEquals(
        List.of(OptionalDouble.empty(), OptionalDouble.of(0), OptionalDouble.of(0)),
        FinancialIndicators.dailyReturns(series));
    assertEquals(
        List.of(OptionalDouble.empty(), OptionalDouble.of(12.5), OptionalDouble.of(12.5)),
        FinancialIndicators.simpleMovingAverage(series, 2));
  }

  @Test
  void increasingPricesProducePositiveReturns() {
    assertEquals(
        List.of(OptionalDouble.empty(), OptionalDouble.of(1), OptionalDouble.of(1)),
        FinancialIndicators.dailyReturns(series("10", "20", "40")));
  }

  @Test
  void decreasingPricesProduceNegativeReturns() {
    assertEquals(
        List.of(OptionalDouble.empty(), OptionalDouble.of(-0.5), OptionalDouble.of(-0.5)),
        FinancialIndicators.dailyReturns(series("40", "20", "10")));
  }

  @Test
  void calculationsUseRawCloseRatherThanAdjustedClose() {
    HistoricalPriceSeries series =
        new HistoricalPriceSeries(
            "TEST",
            List.of(
                bar(0, "100", Optional.of(new BigDecimal("50"))),
                bar(1, "110", Optional.of(new BigDecimal("50")))));

    assertValue(0.1, FinancialIndicators.dailyReturns(series).get(1));
    assertValue(105, FinancialIndicators.simpleMovingAverage(series, 2).get(1));
  }

  @Test
  void missingCalendarDatesUsePrecedingAvailableObservation() {
    HistoricalPriceSeries series =
        new HistoricalPriceSeries(
            "TEST", List.of(bar(0, "100", Optional.empty()), bar(3, "110", Optional.empty())));

    assertEquals(2, FinancialIndicators.dailyReturns(series).size());
    assertValue(0.1, FinancialIndicators.dailyReturns(series).get(1));
    assertValue(105, FinancialIndicators.simpleMovingAverage(series, 2).get(1));
  }

  @Test
  void decimalPricesDoNotAccumulateBinaryFloatingPointErrorBeforeConversion() {
    List<OptionalDouble> averages =
        FinancialIndicators.simpleMovingAverage(series("0.1", "0.2", "0.3"), 3);

    assertEquals(0.2, averages.get(2).orElseThrow());
    assertValue(1.0 / 3.0 - 1, FinancialIndicators.dailyReturns(series("3", "1")).get(1));
  }

  @Test
  void rollingSumRetainsSmallClosesWhenLargeClosesLeaveWindow() {
    List<OptionalDouble> averages =
        FinancialIndicators.simpleMovingAverage(series("1E+100", "1", "3"), 2);

    assertValue(2, averages.get(2));
  }

  @Test
  void dailyReturnsCanUsePricesBeyondDoubleRangeWhenTheirRatioIsFinite() {
    assertValue(1, FinancialIndicators.dailyReturns(series("1E+400", "2E+400")).get(1));
  }

  @Test
  void dailyReturnRetainsTinyRelativeChangeBeforeDoubleConversion() {
    double actual =
        FinancialIndicators.dailyReturns(series("1", "1.000000000000000000000000000000000001"))
            .get(1)
            .orElseThrow();

    assertEquals(1e-36, actual, 1e-48);
  }

  @Test
  void rejectsMovingAverageOutsideFiniteDoubleRange() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FinancialIndicators.simpleMovingAverage(series("1E+400"), 1));
  }

  @Test
  void rejectsDailyReturnOutsideFiniteDoubleRange() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FinancialIndicators.dailyReturns(series("1", "1E+400")));
  }

  @Test
  void appendingFutureBarsDoesNotChangeEarlierIndicatorValues() {
    HistoricalPriceSeries prefix = series("100", "110", "99");
    HistoricalPriceSeries extended = series("100", "110", "99", "1000");

    assertEquals(
        FinancialIndicators.dailyReturns(prefix),
        FinancialIndicators.dailyReturns(extended).subList(0, 3));
    assertEquals(
        FinancialIndicators.simpleMovingAverage(prefix, 2),
        FinancialIndicators.simpleMovingAverage(extended, 2).subList(0, 3));
  }

  @Test
  void indicatorResultsAreUnmodifiable() {
    HistoricalPriceSeries series = series("10", "20");

    assertThrows(
        UnsupportedOperationException.class,
        () -> FinancialIndicators.dailyReturns(series).clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> FinancialIndicators.simpleMovingAverage(series, 1).clear());
  }

  @Test
  void rejectsNullSeries() {
    assertThrows(NullPointerException.class, () -> FinancialIndicators.dailyReturns(null));
    assertThrows(
        NullPointerException.class, () -> FinancialIndicators.simpleMovingAverage(null, 2));
  }

  private static HistoricalPriceSeries series(String... closes) {
    List<PriceBar> bars = new ArrayList<>();
    for (int index = 0; index < closes.length; index++) {
      bars.add(bar(index, closes[index], Optional.empty()));
    }
    return new HistoricalPriceSeries("TEST", bars);
  }

  private static PriceBar bar(int offset, String close, Optional<BigDecimal> adjustedClose) {
    BigDecimal price = new BigDecimal(close);
    return new PriceBar(
        "TEST",
        LocalDate.of(2024, 1, 2).plusDays(offset),
        price,
        price,
        price,
        price,
        100,
        adjustedClose);
  }

  private static void assertValue(double expected, OptionalDouble actual) {
    assertEquals(expected, actual.orElseThrow(), TOLERANCE);
  }
}
