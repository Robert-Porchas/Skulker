package dev.skulker.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PriceBarTest {
  private static final LocalDate DATE = LocalDate.of(2024, 1, 2);
  private static final BigDecimal TEN = new BigDecimal("10.00");

  @Test
  void preservesValidatedValuesAndOptionalAdjustedClose() {
    PriceBar bar = new PriceBar("aapl", DATE, TEN, TEN, TEN, TEN, 0, Optional.of(TEN));

    assertEquals("aapl", bar.symbol());
    assertEquals(DATE, bar.date());
    assertEquals(TEN, bar.open());
    assertEquals(TEN, bar.high());
    assertEquals(TEN, bar.low());
    assertEquals(TEN, bar.close());
    assertEquals(0, bar.volume());
    assertEquals(Optional.of(TEN), bar.adjustedClose());
  }

  @Test
  void convenienceConstructorLeavesAdjustedCloseAbsent() {
    assertEquals(Optional.empty(), bar("TEST", TEN, TEN, TEN, TEN, 1).adjustedClose());
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", " TEST", "TEST ", "\tTEST", "TEST\n"})
  void rejectsInvalidSymbol(String symbol) {
    Class<? extends RuntimeException> expected =
        symbol == null ? NullPointerException.class : IllegalArgumentException.class;
    assertThrows(expected, () -> bar(symbol, TEN, TEN, TEN, TEN, 1));
  }

  @Test
  void rejectsMissingDate() {
    assertThrows(
        NullPointerException.class, () -> new PriceBar("TEST", null, TEN, TEN, TEN, TEN, 1));
  }

  @ParameterizedTest(name = "rejects invalid OHLC tuple {index}")
  @MethodSource("invalidPrices")
  void rejectsNullNonpositiveOrInconsistentPrices(
      BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
    Class<? extends RuntimeException> expected =
        open == null || high == null || low == null || close == null
            ? NullPointerException.class
            : IllegalArgumentException.class;
    assertThrows(expected, () -> bar("TEST", open, high, low, close, 1));
  }

  static Stream<Arguments> invalidPrices() {
    BigDecimal zero = BigDecimal.ZERO;
    BigDecimal negative = BigDecimal.ONE.negate();
    BigDecimal eleven = new BigDecimal("11");
    BigDecimal nine = new BigDecimal("9");
    return Stream.of(
        Arguments.of(null, TEN, TEN, TEN),
        Arguments.of(TEN, null, TEN, TEN),
        Arguments.of(TEN, TEN, null, TEN),
        Arguments.of(TEN, TEN, TEN, null),
        Arguments.of(zero, TEN, TEN, TEN),
        Arguments.of(TEN, zero, TEN, TEN),
        Arguments.of(TEN, TEN, zero, TEN),
        Arguments.of(TEN, TEN, TEN, zero),
        Arguments.of(negative, TEN, TEN, TEN),
        Arguments.of(TEN, negative, TEN, TEN),
        Arguments.of(TEN, TEN, negative, TEN),
        Arguments.of(TEN, TEN, TEN, negative),
        Arguments.of(eleven, TEN, nine, TEN),
        Arguments.of(nine, eleven, TEN, TEN),
        Arguments.of(TEN, TEN, nine, eleven),
        Arguments.of(TEN, eleven, TEN, nine),
        Arguments.of(TEN, nine, eleven, TEN));
  }

  @Test
  void allowsOpenAndCloseAtHighAndLowBounds() {
    PriceBar bar = bar("TEST", new BigDecimal("9"), TEN, new BigDecimal("9"), TEN, 1);

    assertEquals(new BigDecimal("9"), bar.open());
    assertEquals(TEN, bar.close());
  }

  @Test
  void rejectsNegativeVolume() {
    assertThrows(IllegalArgumentException.class, () -> bar("TEST", TEN, TEN, TEN, TEN, -1));
  }

  @Test
  void rejectsNullAdjustedCloseContainer() {
    assertThrows(
        NullPointerException.class, () -> new PriceBar("TEST", DATE, TEN, TEN, TEN, TEN, 1, null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-0.01"})
  void rejectsNonpositiveAdjustedClose(String adjustedClose) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PriceBar(
                "TEST", DATE, TEN, TEN, TEN, TEN, 1, Optional.of(new BigDecimal(adjustedClose))));
  }

  @Test
  void adjustedCloseMayDifferFromRawPriceRange() {
    PriceBar bar = new PriceBar("TEST", DATE, TEN, TEN, TEN, TEN, 1, Optional.of(BigDecimal.ONE));

    assertEquals(Optional.of(BigDecimal.ONE), bar.adjustedClose());
  }

  private static PriceBar bar(
      String symbol,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      long volume) {
    return new PriceBar(symbol, DATE, open, high, low, close, volume);
  }
}
