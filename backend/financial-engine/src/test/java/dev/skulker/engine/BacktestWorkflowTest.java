package dev.skulker.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.skulker.engine.backtest.BacktestResult;
import dev.skulker.engine.backtest.MovingAverageBacktester;
import dev.skulker.engine.backtest.StrategyConfiguration;
import dev.skulker.engine.backtest.Trade;
import dev.skulker.engine.csv.CsvPriceParser;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BacktestWorkflowTest {
  @Test
  void crossoverUsesBothRollingWindowsForNontrivialParameters() {
    var bars = new java.util.ArrayList<PriceBar>();
    String[] closes = {"3", "2", "1", "2", "4", "5"};
    for (int index = 0; index < closes.length; index++) {
      BigDecimal price = new BigDecimal(closes[index]);
      bars.add(
          new PriceBar(
              "TEST", LocalDate.of(2024, 1, 2).plusDays(index), price, price, price, price, 100));
    }
    var configuration = new StrategyConfiguration(2, 3, new BigDecimal("100"), BigDecimal.ZERO);
    var result =
        new MovingAverageBacktester().run(new HistoricalPriceSeries("TEST", bars), configuration);

    assertEquals(1, result.trades().size());
    assertEquals(LocalDate.of(2024, 1, 6), result.trades().getFirst().signalDate());
    assertEquals(LocalDate.of(2024, 1, 7), result.trades().getFirst().executionDate());
    assertEquals(20, result.trades().getFirst().quantity());
  }

  @Test
  void parsedPricesExecuteAtNextAvailableOpenAndRetainTerminalPosition() throws IOException {
    String csv =
        """
        Date,Open,High,Low,Close,Volume
        2024-01-02,3,3,3,3,100
        2024-01-03,2,2,2,2,100
        2024-01-04,1,1,1,1,100
        2024-01-05,2,2,2,2,100
        2024-01-08,10,10,3,3,100
        2024-01-09,2,2,2,2,100
        """;
    HistoricalPriceSeries prices = new CsvPriceParser().parse("TEST", new StringReader(csv));
    StrategyConfiguration configuration =
        new StrategyConfiguration(1, 2, new BigDecimal("100"), BigDecimal.ONE);
    MovingAverageBacktester backtester = new MovingAverageBacktester();

    BacktestResult result = backtester.run(prices, configuration);

    assertEquals(1, result.trades().size());
    Trade buy = result.trades().getFirst();
    assertEquals(LocalDate.of(2024, 1, 5), buy.signalDate());
    assertEquals(LocalDate.of(2024, 1, 8), buy.executionDate());
    assertEquals(Trade.Side.BUY, buy.side());
    assertEquals(9, buy.quantity());
    assertEquals(0, new BigDecimal("10").compareTo(buy.price()));
    assertEquals(0, new BigDecimal("9").compareTo(buy.cashAfter()));
    assertEquals(0, new BigDecimal("27").compareTo(result.finalPortfolioValue()));
    assertEquals(-0.73, result.totalReturn(), 1e-12);
    assertEquals(0.73, result.maximumDrawdown(), 1e-12);
    assertEquals(9, result.equityCurve().getLast().shares());
    assertEquals(result, backtester.run(prices, configuration));
  }
}
