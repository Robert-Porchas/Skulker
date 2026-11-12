package dev.skulker.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
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

  @Test
  void cumulativeReturnsUseInitialCloseAndStartAtZero() {
    assertEquals(
        List.of(OptionalDouble.of(0), OptionalDouble.of(0.1), OptionalDouble.of(-0.01)),
        FinancialIndicators.cumulativeReturns(series("100", "110", "99")));
    assertEquals(
        List.of(OptionalDouble.of(0)), FinancialIndicators.cumulativeReturns(series("100")));
  }

  @Test
  void cumulativeReturnsAreNotTheSumOfDailyReturns() {
    assertValue(0.21, FinancialIndicators.cumulativeReturns(series("100", "110", "121")).get(2));
    assertValue(-0.75, FinancialIndicators.cumulativeReturns(series("100", "50", "25")).get(2));
  }

  @Test
  void cumulativeReturnsPreserveTinyChangesAndFiniteRatiosFromLargePrices() {
    assertEquals(
        1e-36,
        FinancialIndicators.cumulativeReturns(series("1", "1.000000000000000000000000000000000001"))
            .get(1)
            .orElseThrow(),
        1e-48);
    assertValue(1, FinancialIndicators.cumulativeReturns(series("1E+400", "2E+400")).get(1));
  }

  @Test
  void rejectsCumulativeReturnOutsideFiniteDoubleRange() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FinancialIndicators.cumulativeReturns(series("1", "1E+400")));
  }

  @Test
  void volatilityUsesTrailingSampleStandardDeviationOfDailyReturns() {
    List<OptionalDouble> actual =
        FinancialIndicators.rollingVolatility(series("100", "110", "99", "118.8"), 2);

    assertEquals(4, actual.size());
    assertEquals(OptionalDouble.empty(), actual.get(0));
    assertEquals(OptionalDouble.empty(), actual.get(1));
    assertValue(Math.sqrt(0.02), actual.get(2));
    assertValue(Math.sqrt(0.045), actual.get(3));
  }

  @Test
  void volatilityUsesWindowMinusOneDenominatorForThreeReturns() {
    assertValue(
        Math.sqrt(7.0 / 300),
        FinancialIndicators.rollingVolatility(series("100", "110", "99", "118.8"), 3).get(3));
  }

  @Test
  void constantPricesAndConstantReturnsHaveZeroVolatility() {
    assertEquals(
        List.of(OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.of(0)),
        FinancialIndicators.rollingVolatility(series("100", "100", "100"), 2));
    assertValue(0, FinancialIndicators.rollingVolatility(series("10", "20", "40"), 2).get(2));
  }

  @Test
  void volatilityRequiresWindowPlusOnePrices() {
    assertEquals(
        List.of(OptionalDouble.empty(), OptionalDouble.empty()),
        FinancialIndicators.rollingVolatility(series("100", "110"), 2));
    assertEquals(
        List.of(OptionalDouble.empty()),
        FinancialIndicators.rollingVolatility(series("100"), Integer.MAX_VALUE));
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1})
  void rejectsVolatilityWindowBelowTwoEvenForEmptySeries(int window) {
    assertThrows(
        IllegalArgumentException.class,
        () -> FinancialIndicators.rollingVolatility(series(), window));
    assertThrows(
        IllegalArgumentException.class,
        () -> FinancialIndicators.rollingVolatility(series("100", "110", "99"), window));
  }

  @Test
  void volatilityRetainsTinyDispersionWithoutSquaredSumsCancellation() {
    assertEquals(
        Math.sqrt(2) * 1e-36,
        FinancialIndicators.rollingVolatility(
                series("1", "1.000000000000000000000000000000000001", "1"), 2)
            .get(2)
            .orElseThrow(),
        1e-48);
  }

  @Test
  void volatilityDoesNotOverflowWhenIntermediateSquaresExceedDoubleRange() {
    assertEquals(
        Math.sqrt(0.5) * 1e200,
        FinancialIndicators.rollingVolatility(series("1", "1E+200", "1E+200"), 2)
            .get(2)
            .orElseThrow(),
        1e188);
  }

  @Test
  void rejectsVolatilityOutsideFiniteDoubleRange() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FinancialIndicators.rollingVolatility(series("1", "1E+400", "1E+400"), 2));
  }

  @Test
  void cumulativeReturnsAndVolatilityUseObservedBarsAcrossCalendarGaps() {
    HistoricalPriceSeries withGaps =
        new HistoricalPriceSeries(
            "TEST",
            List.of(
                bar(0, "100", Optional.empty()),
                bar(3, "110", Optional.empty()),
                bar(7, "99", Optional.empty())));

    assertValue(-0.01, FinancialIndicators.cumulativeReturns(withGaps).get(2));
    assertValue(Math.sqrt(0.02), FinancialIndicators.rollingVolatility(withGaps, 2).get(2));
  }

  @Test
  void emptySeriesHasNoCumulativeReturnsOrVolatility() {
    assertEquals(List.of(), FinancialIndicators.cumulativeReturns(series()));
    assertEquals(List.of(), FinancialIndicators.rollingVolatility(series(), 2));
  }

  @Test
  void newIndicatorResultsAreUnmodifiableAndRejectNullSeries() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> FinancialIndicators.cumulativeReturns(series("100")).clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> FinancialIndicators.rollingVolatility(series("100"), 2).clear());
    assertThrows(NullPointerException.class, () -> FinancialIndicators.cumulativeReturns(null));
    assertThrows(NullPointerException.class, () -> FinancialIndicators.rollingVolatility(null, 2));
  }

  @Test
  void appendingFuturePricesPreservesEarlierCumulativeReturnsAndVolatility() {
    HistoricalPriceSeries prefix = series("100", "110", "99");
    HistoricalPriceSeries extended = series("100", "110", "99", "1000");

    assertEquals(
        FinancialIndicators.cumulativeReturns(prefix),
        FinancialIndicators.cumulativeReturns(extended).subList(0, 3));
    assertEquals(
        FinancialIndicators.rollingVolatility(prefix, 2),
        FinancialIndicators.rollingVolatility(extended, 2).subList(0, 3));
  }

  @Test
  void drawdownMeasuresWorstLossFromRunningPeakAndSurvivesRecovery() {
    assertValue(
        0.25, FinancialIndicators.maximumDrawdown(equity("100", "120", "90", "110", "150", "120")));
  }

  @Test
  void drawdownUpdatesPeakAfterRecovery() {
    assertValue(0.4, FinancialIndicators.maximumDrawdown(equity("100", "90", "200", "120")));
  }

  @Test
  void drawdownIsZeroForSingleConstantOrRisingEquity() {
    assertValue(0, FinancialIndicators.maximumDrawdown(equity("100")));
    assertValue(0, FinancialIndicators.maximumDrawdown(equity("100", "100", "100")));
    assertValue(0, FinancialIndicators.maximumDrawdown(equity("100", "110", "120")));
  }

  @Test
  void drawdownCanRepresentTotalLossWithoutNegativeEquity() {
    assertValue(1, FinancialIndicators.maximumDrawdown(equity("100", "0", "0")));
    assertValue(1, FinancialIndicators.maximumDrawdown(equity("100", "0", "200")));
  }

  @Test
  void drawdownHandlesLargeEquityAndTinyRepresentableLosses() {
    assertValue(0.5, FinancialIndicators.maximumDrawdown(equity("1E+400", "5E+399")));
    assertEquals(
        1e-36,
        FinancialIndicators.maximumDrawdown(equity("1", "0.999999999999999999999999999999999999"))
            .orElseThrow(),
        1e-48);
  }

  @Test
  void emptyEquityHasUnavailableDrawdown() {
    assertEquals(OptionalDouble.empty(), FinancialIndicators.maximumDrawdown(List.of()));
  }

  @Test
  void drawdownRejectsZeroInitialEquityNegativeValuesAndNulls() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FinancialIndicators.maximumDrawdown(equity("0", "100")));
    assertThrows(
        IllegalArgumentException.class,
        () -> FinancialIndicators.maximumDrawdown(equity("-100", "100")));
    assertThrows(
        IllegalArgumentException.class,
        () -> FinancialIndicators.maximumDrawdown(equity("100", "-1")));
    assertThrows(NullPointerException.class, () -> FinancialIndicators.maximumDrawdown(null));
    assertThrows(
        NullPointerException.class,
        () -> FinancialIndicators.maximumDrawdown(Arrays.asList(BigDecimal.TEN, null)));
    assertThrows(
        NullPointerException.class,
        () -> FinancialIndicators.maximumDrawdown(Arrays.asList(null, BigDecimal.TEN)));
  }

  private static List<BigDecimal> equity(String... values) {
    return Arrays.stream(values).map(BigDecimal::new).toList();
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
