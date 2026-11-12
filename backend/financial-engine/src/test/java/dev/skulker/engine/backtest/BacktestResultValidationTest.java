package dev.skulker.engine.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class BacktestResultValidationTest {
  private static final LocalDate START = LocalDate.of(2024, 1, 2);
  private static final List<EquityPoint> CURVE =
      List.of(point(0, false), point(1, true), point(2, true), point(3, false));
  private static final Trade BUY = trade(0, 1, Trade.Side.BUY);
  private static final Trade SELL = trade(2, 3, Trade.Side.SELL);

  @Test
  void acceptsChronologicalHistoryButRejectsDuplicateOrReorderedIntermediateEquityDates() {
    BacktestResult valid = result(CURVE, List.of(BUY, SELL));

    assertEquals(CURVE, valid.equityCurve());
    assertEquals(List.of(BUY, SELL), valid.trades());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            result(
                List.of(CURVE.get(0), CURVE.get(1), CURVE.get(1), CURVE.get(3)),
                List.of(BUY, SELL)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            result(
                List.of(CURVE.get(0), CURVE.get(2), CURVE.get(1), CURVE.get(3)),
                List.of(BUY, SELL)));
  }

  @Test
  void rejectsTradeSignalsBeforeStartOrExecutionsAfterEnd() {
    assertThrows(
        IllegalArgumentException.class, () -> result(CURVE, List.of(trade(-1, 1, Trade.Side.BUY))));
    assertThrows(
        IllegalArgumentException.class,
        () -> result(CURVE, List.of(BUY, trade(2, 4, Trade.Side.SELL))));
  }

  @Test
  void rejectsDuplicateOrReorderedTradeExecutionDates() {
    assertThrows(
        IllegalArgumentException.class,
        () -> result(CURVE, List.of(BUY, trade(0, 1, Trade.Side.SELL))));
    assertThrows(IllegalArgumentException.class, () -> result(CURVE, List.of(SELL, BUY)));
  }

  private static BacktestResult result(List<EquityPoint> curve, List<Trade> trades) {
    StrategyConfiguration configuration =
        new StrategyConfiguration(1, 2, BigDecimal.TEN, BigDecimal.ZERO);
    return new BacktestResult(
        configuration,
        "TEST",
        START,
        START.plusDays(3),
        BigDecimal.TEN,
        BigDecimal.TEN,
        0,
        OptionalDouble.of(0),
        0,
        trades,
        curve,
        Map.of());
  }

  private static EquityPoint point(int day, boolean holding) {
    return new EquityPoint(
        START.plusDays(day),
        holding ? BigDecimal.ZERO : BigDecimal.TEN,
        holding ? 1 : 0,
        BigDecimal.TEN,
        BigDecimal.TEN);
  }

  private static Trade trade(int signalDay, int executionDay, Trade.Side side) {
    return new Trade(
        START.plusDays(signalDay),
        START.plusDays(executionDay),
        side,
        1,
        BigDecimal.TEN,
        BigDecimal.ZERO,
        side == Trade.Side.BUY ? BigDecimal.ZERO : BigDecimal.TEN);
  }
}
