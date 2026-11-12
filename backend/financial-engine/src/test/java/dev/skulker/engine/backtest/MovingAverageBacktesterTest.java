package dev.skulker.engine.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skulker.engine.HistoricalPriceSeries;
import dev.skulker.engine.PriceBar;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class MovingAverageBacktesterTest {
  private static final LocalDate START = LocalDate.of(2024, 1, 2);
  private static final double TOLERANCE = 1e-12;
  private final MovingAverageBacktester backtester = new MovingAverageBacktester();

  @Test
  void signalAtCloseExecutesAtNextOpenRatherThanSignalClose() {
    BacktestResult result =
        backtester.run(
            series(new String[] {"10", "9", "10", "20"}, "10", "9", "10", "12"),
            config("100", "1"));

    assertEquals(1, result.trades().size());
    Trade trade = result.trades().getFirst();
    assertEquals(START.plusDays(2), trade.signalDate());
    assertEquals(START.plusDays(3), trade.executionDate());
    assertEquals(Trade.Side.BUY, trade.side());
    assertEquals(4, trade.quantity());
    assertDecimal("20", trade.price());
    assertDecimal("1", trade.fee());
    assertDecimal("19", trade.cashAfter());
    assertDecimal("67", result.finalPortfolioValue());
  }

  @Test
  void fullRoundTripChargesEachExecutedTradeAndValuesPortfolioExactly() {
    BacktestResult result = backtester.run(roundTrip(), config("100", "1"));

    assertEquals(2, result.trades().size());
    Trade buy = result.trades().get(0);
    Trade sell = result.trades().get(1);
    assertEquals(9, buy.quantity());
    assertEquals(Trade.Side.SELL, sell.side());
    assertEquals(9, sell.quantity());
    assertEquals(START.plusDays(4), sell.signalDate());
    assertEquals(START.plusDays(5), sell.executionDate());
    assertDecimal("7", sell.price());
    assertDecimal("1", sell.fee());
    assertDecimal("71", sell.cashAfter());
    assertDecimal("71", result.finalPortfolioValue());
    assertEquals(-0.29, result.totalReturn(), TOLERANCE);
    assertDecimal("117", result.equityCurve().get(3).portfolioValue());
    assertDecimal("81", result.equityCurve().get(4).portfolioValue());
    assertEquals(0, result.equityCurve().getLast().shares());
  }

  @Test
  void drawdownUsesPeakEquityAndIncludesInitialCapitalBaseline() {
    BacktestResult result = backtester.run(roundTrip(), config("100", "1"));

    assertEquals(46.0 / 117, result.maximumDrawdown(), TOLERANCE);
    BacktestResult losingAtEntry =
        backtester.run(
            series(new String[] {"10", "9", "10", "20"}, "10", "9", "10", "12"),
            config("100", "1"));
    assertEquals(0.33, losingAtEntry.maximumDrawdown(), TOLERANCE);
  }

  @Test
  void buyingReservesFeeAndUsesAllAffordableWholeShares() {
    BacktestResult result =
        backtester.run(
            series(new String[] {"10", "9", "10", "10"}, "10", "9", "10", "10"),
            config("101", "1"));

    assertEquals(10, result.trades().getFirst().quantity());
    assertDecimal("0", result.trades().getFirst().cashAfter());
    assertDecimal("100", result.finalPortfolioValue());
  }

  @Test
  void fractionalShareRemainderStaysInCash() {
    BacktestResult result =
        backtester.run(
            series(new String[] {"10", "9", "10", "30"}, "10", "9", "10", "12"),
            config("100", "0"));

    assertEquals(3, result.trades().getFirst().quantity());
    assertDecimal("10", result.trades().getFirst().cashAfter());
    assertDecimal("46", result.finalPortfolioValue());
  }

  @Test
  void cashAccountingKeepsExactDecimalAmountsWithoutCurrencyRounding() {
    BacktestResult result =
        backtester.run(
            series(new String[] {"0.3", "0.2", "0.3", "0.3"}, "0.3", "0.2", "0.3", "0.3"),
            config("1.0001", "0.0001"));

    assertEquals(3, result.trades().getFirst().quantity());
    assertDecimal("0.1000", result.trades().getFirst().cashAfter());
    assertDecimal("1.0000", result.finalPortfolioValue());
  }

  @Test
  void unaffordableBuyIsSkippedWithoutFeeOrAutomaticRetry() {
    BacktestResult result =
        backtester.run(
            series(new String[] {"10", "9", "10", "200", "10"}, "10", "9", "10", "12", "13"),
            config("100", "1"));

    assertTrue(result.trades().isEmpty());
    assertDecimal("100", result.finalPortfolioValue());
    assertEquals(0, result.totalReturn());
  }

  @Test
  void feeLargerThanCashPreventsBuyWithoutNegativeBalances() {
    BacktestResult result = backtester.run(closes("10", "9", "10", "11"), config("100", "101"));

    assertTrue(result.trades().isEmpty());
    assertDecimal("100", result.finalPortfolioValue());
  }

  @Test
  void exitIsSkippedWhenCashAndSaleProceedsCannotPayFee() {
    BacktestResult result =
        backtester.run(
            series(new String[] {"10", "9", "10", "10", "1", "1"}, "10", "9", "10", "11", "1", "1"),
            config("100", "30"));

    assertEquals(1, result.trades().size());
    assertEquals(7, result.equityCurve().getLast().shares());
    assertDecimal("0", result.equityCurve().getLast().cash());
    assertDecimal("7", result.finalPortfolioValue());
  }

  @Test
  void exitMayConsumeExactlyAllCashAndSaleProceeds() {
    BacktestResult result =
        backtester.run(
            series(
                new String[] {"10", "9", "10", "25", "1", "25"}, "10", "9", "10", "11", "1", "1"),
            config("100", "50"));

    assertEquals(2, result.trades().size());
    assertDecimal("0", result.finalPortfolioValue());
    assertEquals(-1, result.totalReturn());
    assertEquals(-1, result.annualizedReturn().orElseThrow());
    assertEquals(1, result.maximumDrawdown());
  }

  @Test
  void openPositionAtDatasetEndIsMarkedToCloseWithoutLiquidationOrExitFee() {
    BacktestResult result =
        backtester.run(
            series(new String[] {"10", "9", "10", "10"}, "10", "9", "10", "12"),
            config("100", "1"));

    assertEquals(1, result.trades().size());
    assertEquals(9, result.equityCurve().getLast().shares());
    assertDecimal("9", result.equityCurve().getLast().cash());
    assertDecimal("117", result.finalPortfolioValue());
    assertEquals(0.17, result.totalReturn(), TOLERANCE);
  }

  @ParameterizedTest
  @MethodSource("noCrossoverSeries")
  void constantOrMonotonicPricesDoNotInventAnInitialCrossover(HistoricalPriceSeries series) {
    BacktestResult result = backtester.run(series, config("100", "0"));

    assertTrue(result.trades().isEmpty());
    assertDecimal("100", result.finalPortfolioValue());
    assertEquals(0, result.totalReturn());
    assertEquals(0, result.maximumDrawdown());
  }

  static List<HistoricalPriceSeries> noCrossoverSeries() {
    return List.of(
        closes("10", "10", "10", "10"), closes("1", "2", "3", "4"), closes("4", "3", "2", "1"));
  }

  @Test
  void crossoverOnFinalAvailableDayCannotExecute() {
    BacktestResult result = backtester.run(closes("10", "9", "10"), config("100", "1"));

    assertTrue(result.trades().isEmpty());
    assertDecimal("100", result.finalPortfolioValue());
  }

  @Test
  void finalDayExitSignalLeavesPositionOpen() {
    BacktestResult result =
        backtester.run(
            series(new String[] {"10", "9", "10", "10", "8"}, "10", "9", "10", "12", "8"),
            config("100", "1"));

    assertEquals(1, result.trades().size());
    assertEquals(9, result.equityCurve().getLast().shares());
    assertDecimal("81", result.finalPortfolioValue());
  }

  @Test
  void insufficientHistoryReturnsUninvestedCash() {
    BacktestResult result =
        backtester.run(
            closes("10", "9", "10"),
            new StrategyConfiguration(2, 5, new BigDecimal("100"), BigDecimal.ONE));

    assertTrue(result.trades().isEmpty());
    assertEquals(3, result.equityCurve().size());
    assertDecimal("100", result.finalPortfolioValue());
  }

  @Test
  void firstAvailableMovingAveragesCannotCreateSignalWithoutPriorAverages() {
    BacktestResult result = backtester.run(closes("9", "10", "11"), config("100", "0"));

    assertTrue(result.trades().isEmpty());
  }

  @Test
  void equalityDoesNotSignalButLaterCrossFromEqualityDoes() {
    BacktestResult result = backtester.run(closes("10", "9", "9", "10", "11"), config("100", "0"));

    assertEquals(1, result.trades().size());
    assertEquals(START.plusDays(3), result.trades().getFirst().signalDate());
    assertEquals(START.plusDays(4), result.trades().getFirst().executionDate());
  }

  @Test
  void downCrossFromEqualityExitsExactlyOnce() {
    BacktestResult result =
        backtester.run(closes("10", "9", "10", "10", "9", "8", "7"), config("100", "0"));

    assertEquals(2, result.trades().size());
    assertEquals(START.plusDays(4), result.trades().getLast().signalDate());
    assertEquals(Trade.Side.SELL, result.trades().getLast().side());
  }

  @Test
  void exactDecimalSmaComparisonRetainsCrossTooSmallForDouble() {
    BacktestResult result =
        backtester.run(
            closes(
                "1.000000000000000000000000000000000002",
                "1",
                "1.000000000000000000000000000000000001",
                "1"),
            config("100", "0"));

    assertEquals(1, result.trades().size());
    assertEquals(START.plusDays(2), result.trades().getFirst().signalDate());
  }

  @Test
  void executionUsesNextSuppliedDateAcrossCalendarGaps() {
    List<PriceBar> bars =
        List.of(bar(0, "10", "10"), bar(1, "9", "9"), bar(2, "10", "10"), bar(8, "11", "11"));
    BacktestResult result =
        backtester.run(new HistoricalPriceSeries("TEST", bars), config("100", "0"));

    assertEquals(START.plusDays(2), result.trades().getFirst().signalDate());
    assertEquals(START.plusDays(8), result.trades().getFirst().executionDate());
  }

  @Test
  void changingFutureDataLeavesEarlierEquityAndTradesUnchanged() {
    BacktestResult prefix = backtester.run(closes("10", "9", "10", "12"), config("100", "1"));
    BacktestResult extended =
        backtester.run(closes("10", "9", "10", "12", "1000"), config("100", "1"));

    assertEquals(prefix.trades(), extended.trades());
    assertEquals(prefix.equityCurve(), extended.equityCurve().subList(0, 4));
  }

  @Test
  void annualizationUsesActualElapsedCalendarDaysAnd365Point25Year() {
    List<PriceBar> bars =
        List.of(bar(0, "10", "10"), bar(100, "9", "9"), bar(200, "10", "10"), bar(366, "10", "12"));
    BacktestResult result =
        backtester.run(new HistoricalPriceSeries("TEST", bars), config("100", "0"));

    assertEquals(0.2, result.totalReturn(), TOLERANCE);
    assertEquals(
        Math.pow(1.2, 365.25 / 366) - 1, result.annualizedReturn().orElseThrow(), TOLERANCE);
  }

  @Test
  void singleBarHasNoAnnualizedReturn() {
    BacktestResult result = backtester.run(closes("10"), config("100", "1"));

    assertFalse(result.annualizedReturn().isPresent());
    assertEquals(START, result.startDate());
    assertEquals(START, result.endDate());
  }

  @ParameterizedTest
  @ValueSource(ints = {16, 40, 400})
  void annualizationPreservesPositiveResidualForSevereLosses(int exponent) {
    BigDecimal capital = BigDecimal.ONE.scaleByPowerOfTen(exponent);
    StrategyConfiguration configuration =
        new StrategyConfiguration(1, 2, capital, capital.subtract(BigDecimal.ONE));
    HistoricalPriceSeries series =
        new HistoricalPriceSeries(
            "TEST",
            List.of(
                bar(0, "1", "1"), bar(1, "0.5", "0.5"), bar(2, "1", "1"), bar(36525, "1", "1")));
    BacktestResult result = backtester.run(series, configuration);

    assertDecimal("1", result.finalPortfolioValue());
    if (exponent >= 40) {
      assertEquals(-1, result.totalReturn());
    }
    assertEquals(
        StrictMath.pow(10, -exponent / 100.0) - 1,
        result.annualizedReturn().orElseThrow(),
        TOLERANCE);
  }

  @Test
  void rejectsAnnualizedReturnOverflowExplicitly() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            backtester.run(
                series(new String[] {"1", "0.5", "1", "1"}, "1", "0.5", "1", "1E+100"),
                config("1", "0")));
  }

  @Test
  void rejectsTotalReturnOverflowExplicitly() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            backtester.run(
                series(new String[] {"1", "0.5", "1", "1"}, "1", "0.5", "1", "1E+400"),
                config("1", "0")));
  }

  @Test
  void rejectsShareQuantityOutsideLongRangeInsteadOfWrapping() {
    assertThrows(
        IllegalArgumentException.class,
        () -> backtester.run(closes("1", "0.5", "1", "1"), config("1E+30", "0")));
  }

  @Test
  void resultsAreRepeatableWithoutWallClockMetadata() {
    BacktestResult first = backtester.run(roundTrip(), config("100", "1"));
    BacktestResult second = backtester.run(roundTrip(), config("100", "1"));

    assertEquals(first, second);
    assertEquals("ACT/365.25", first.executionMetadata().get("annualization"));
    assertEquals("EXACT_DECIMAL_SMA_COMPARISON", first.executionMetadata().get("signalArithmetic"));
  }

  @Test
  void resultCollectionsAreImmutableAndDefensivelyCopied() {
    BacktestResult original = backtester.run(roundTrip(), config("100", "1"));
    ArrayList<Trade> trades = new ArrayList<>(original.trades());
    ArrayList<EquityPoint> curve = new ArrayList<>(original.equityCurve());
    Map<String, String> metadata = new HashMap<>(original.executionMetadata());
    BacktestResult copied =
        new BacktestResult(
            original.configuration(),
            original.symbol(),
            original.startDate(),
            original.endDate(),
            original.initialCapital(),
            original.finalPortfolioValue(),
            original.totalReturn(),
            original.annualizedReturn(),
            original.maximumDrawdown(),
            trades,
            curve,
            metadata);
    trades.clear();
    curve.clear();
    metadata.clear();

    assertEquals(original, copied);
    assertThrows(UnsupportedOperationException.class, () -> copied.trades().clear());
    assertThrows(UnsupportedOperationException.class, () -> copied.equityCurve().clear());
    assertThrows(UnsupportedOperationException.class, () -> copied.executionMetadata().clear());
  }

  @Test
  void allRecordedCashHoldingsAndPortfolioValuesStayNonnegative() {
    BacktestResult result =
        backtester.run(
            closes("10", "9", "10", "12", "8", "7", "8", "10", "6", "4"), config("100", "1"));

    assertTrue(
        result.equityCurve().stream()
            .allMatch(
                point ->
                    point.cash().signum() >= 0
                        && point.shares() >= 0
                        && point.portfolioValue().signum() >= 0));
    assertTrue(
        result.trades().stream()
            .allMatch(trade -> trade.executionDate().isAfter(trade.signalDate())));
  }

  @Test
  void rejectsEmptyOrNullInputs() {
    assertThrows(
        IllegalArgumentException.class, () -> backtester.run(closes(), config("100", "0")));
    assertThrows(NullPointerException.class, () -> backtester.run(null, config("100", "0")));
    assertThrows(NullPointerException.class, () -> backtester.run(closes("10"), null));
  }

  private static StrategyConfiguration config(String capital, String fee) {
    return new StrategyConfiguration(1, 2, new BigDecimal(capital), new BigDecimal(fee));
  }

  private static HistoricalPriceSeries roundTrip() {
    return series(new String[] {"10", "9", "10", "10", "8", "7"}, "10", "9", "10", "12", "8", "7");
  }

  private static HistoricalPriceSeries closes(String... closes) {
    return series(closes, closes);
  }

  private static HistoricalPriceSeries series(String[] opens, String... closes) {
    List<PriceBar> bars = new ArrayList<>();
    for (int index = 0; index < closes.length; index++) {
      bars.add(bar(index, opens[index], closes[index]));
    }
    return new HistoricalPriceSeries("TEST", bars);
  }

  private static PriceBar bar(int dayOffset, String openValue, String closeValue) {
    BigDecimal open = new BigDecimal(openValue);
    BigDecimal close = new BigDecimal(closeValue);
    return new PriceBar(
        "TEST", START.plusDays(dayOffset), open, open.max(close), open.min(close), close, 100);
  }

  private static void assertDecimal(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> expected + " != " + actual);
  }
}
