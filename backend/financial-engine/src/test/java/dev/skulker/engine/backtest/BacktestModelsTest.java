package dev.skulker.engine.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BacktestModelsTest {
  @ParameterizedTest
  @CsvSource({"0,2", "-1,2", "2,2", "3,2", "1,0"})
  void rejectsInvalidWindows(int shortWindow, int longWindow) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new StrategyConfiguration(shortWindow, longWindow, BigDecimal.TEN, BigDecimal.ZERO));
  }

  @Test
  void rejectsInvalidCapitalAndFees() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new StrategyConfiguration(1, 2, BigDecimal.ZERO, BigDecimal.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StrategyConfiguration(1, 2, BigDecimal.ONE.negate(), BigDecimal.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StrategyConfiguration(1, 2, BigDecimal.TEN, BigDecimal.ONE.negate()));
    assertThrows(
        NullPointerException.class, () -> new StrategyConfiguration(1, 2, null, BigDecimal.ZERO));
    assertThrows(
        NullPointerException.class, () -> new StrategyConfiguration(1, 2, BigDecimal.TEN, null));
  }

  @Test
  void configurationIdentifiesStrategyAndAcceptsZeroFee() {
    assertEquals(
        StrategyType.SMA_CROSSOVER,
        new StrategyConfiguration(1, 2, BigDecimal.TEN, BigDecimal.ZERO).strategyType());
  }

  @Test
  void executedTradeRequiresFutureExecutionPositiveQuantityAndNonnegativeCash() {
    LocalDate signal = LocalDate.of(2024, 1, 2);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Trade(
                signal,
                signal,
                Trade.Side.BUY,
                1,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Trade(
                signal,
                signal.plusDays(1),
                Trade.Side.BUY,
                0,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Trade(
                signal,
                signal.plusDays(1),
                Trade.Side.BUY,
                1,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                BigDecimal.ONE.negate()));
  }

  @Test
  void equityPointRejectsInconsistentValuationAndShortPosition() {
    LocalDate date = LocalDate.of(2024, 1, 2);
    assertThrows(
        IllegalArgumentException.class,
        () -> new EquityPoint(date, BigDecimal.ONE, 2, BigDecimal.TEN, BigDecimal.TEN));
    assertThrows(
        IllegalArgumentException.class,
        () -> new EquityPoint(date, BigDecimal.ONE, -1, BigDecimal.TEN, BigDecimal.ONE));
  }

  @Test
  void equityValuationAcceptsEquivalentDecimalScales() {
    EquityPoint point =
        new EquityPoint(
            LocalDate.of(2024, 1, 2),
            new BigDecimal("1.00"),
            2,
            new BigDecimal("10.00"),
            new BigDecimal("21"));
    assertEquals(new BigDecimal("21"), point.portfolioValue());
  }
}
