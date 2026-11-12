package dev.skulker.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skulker.engine.HistoricalPriceSeries;
import dev.skulker.engine.PriceBar;
import dev.skulker.engine.backtest.BacktestResult;
import dev.skulker.engine.backtest.MovingAverageBacktester;
import dev.skulker.engine.backtest.StrategyConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JsonContractTest {
  private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

  @Test
  void strategyConfigurationJsonRoundTripPreservesExactMoney() {
    StrategyConfiguration configuration =
        new StrategyConfiguration(
            1, 2, new BigDecimal("100.12345678"), new BigDecimal("0.00000001"));

    StrategyConfiguration restored =
        json.readValue(json.writeValueAsString(configuration), StrategyConfiguration.class);

    assertEquals(configuration, restored);
  }

  @Test
  void resultJsonRoundTripPreservesDatesOptionalMetricsTradesAndEquity() {
    HistoricalPriceSeries prices =
        new HistoricalPriceSeries(
            "TEST", List.of(bar(0, "10"), bar(1, "9"), bar(2, "10"), bar(3, "12")));
    BacktestResult result =
        new MovingAverageBacktester()
            .run(prices, new StrategyConfiguration(1, 2, new BigDecimal("100"), BigDecimal.ONE));

    BacktestResult restored = json.readValue(json.writeValueAsString(result), BacktestResult.class);

    assertEquals(result, restored);
  }

  @Test
  void singleBarResultSerializesUnavailableAnnualizationAsNullAndRestoresIt() {
    BacktestResult result =
        new MovingAverageBacktester()
            .run(
                new HistoricalPriceSeries("TEST", List.of(bar(0, "10"))),
                new StrategyConfiguration(1, 2, new BigDecimal("100"), BigDecimal.ONE));

    String encoded = json.writeValueAsString(result);

    assertTrue(json.readTree(encoded).get("annualizedReturn").isNull());
    assertEquals(result, json.readValue(encoded, BacktestResult.class));
  }

  private static PriceBar bar(int day, String value) {
    BigDecimal price = new BigDecimal(value);
    return new PriceBar(
        "TEST", LocalDate.of(2024, 1, 2).plusDays(day), price, price, price, price, 100);
  }
}
