package dev.skulker.engine.csv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skulker.engine.HistoricalPriceSeries;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CsvPriceParserTest {
  private static final String HEADER = "Date,Open,High,Low,Close,Volume";
  private static final String ROW = "2024-01-02,100.00,103.00,99.50,102.00,1200000";
  private final CsvPriceParser parser = new CsvPriceParser();

  @Test
  void preservesSymbolDatesPricesAndIntegralVolume() throws IOException {
    HistoricalPriceSeries series =
        parse(HEADER + "\n" + ROW + "\n2024-01-05,102,104,101,103.50,0\n");

    assertEquals("EXAMPLE", series.symbol());
    assertEquals(2, series.bars().size());
    assertEquals(LocalDate.of(2024, 1, 2), series.bars().getFirst().date());
    assertEquals(new BigDecimal("100.00"), series.bars().getFirst().open());
    assertEquals(new BigDecimal("103.00"), series.bars().getFirst().high());
    assertEquals(new BigDecimal("99.50"), series.bars().getFirst().low());
    assertEquals(new BigDecimal("102.00"), series.bars().getFirst().close());
    assertEquals(1200000L, series.bars().getFirst().volume());
    assertEquals(Optional.empty(), series.bars().getFirst().adjustedClose());
    assertEquals(LocalDate.of(2024, 1, 5), series.bars().getLast().date());
    assertEquals(0L, series.bars().getLast().volume());
  }

  @Test
  void acceptsOptionalAdjustedCloseAndEmptyAdjustedField() throws IOException {
    HistoricalPriceSeries series =
        parse(HEADER + ",Adj Close\n" + ROW + ",101.250\n2024-01-03,102,104,101,103,1,\n");

    assertEquals(Optional.of(new BigDecimal("101.250")), series.bars().getFirst().adjustedClose());
    assertEquals(Optional.empty(), series.bars().getLast().adjustedClose());
  }

  @Test
  void acceptsBomCrLfAndBlankPhysicalRows() throws IOException {
    HistoricalPriceSeries series =
        parse("\r\n \t\r\n\uFEFF" + HEADER + "\r\n\r\n" + ROW + "\r\n\t\r\n");

    assertEquals(1, series.bars().size());
  }

  @Test
  void acceptsHeaderOnlyAsEmptyNamedSeries() throws IOException {
    HistoricalPriceSeries series = parse(HEADER + "\n\n");

    assertEquals("EXAMPLE", series.symbol());
    assertTrue(series.bars().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "\n\n", " \t\n"})
  void rejectsInputWithoutAHeader(String csv) {
    assertInvalid(csv, "missing CSV header");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Date,Open,High,Low,Close",
        "Date,Open,High,Low,Close,Volume,Unexpected",
        "Date,Open,High,Low,Close,Volume,Volume",
        "Open,Date,High,Low,Close,Volume",
        "date,Open,High,Low,Close,Volume",
        " Date,Open,High,Low,Close,Volume",
        "Date,Open,High,Low,Close,Volume ",
        "\"Date\",Open,High,Low,Close,Volume"
      })
  void rejectsMissingUnknownDuplicateReorderedAndMalformedHeaders(String csv) {
    assertInvalid(csv, "CSV row 1: expected header");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2024-1-02",
        "2024-01-2",
        "2024-02-30",
        "2023-02-29",
        "0000-01-02",
        "+2024-01-02",
        "2024-01-02T00:00:00",
        "01/02/2024",
        ""
      })
  void rejectsInvalidAndNoncanonicalDates(String date) {
    assertInvalid(HEADER + "\n" + date + ",100,103,99,102,1", "CSV row 2:");
  }

  @Test
  void acceptsRealLeapDay() throws IOException {
    assertEquals(
        LocalDate.of(2024, 2, 29),
        parse(HEADER + "\n2024-02-29,100,103,99,102,1").bars().getFirst().date());
  }

  @ParameterizedTest
  @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "1e2", "+100", ".5", "1.", "", "1_000"})
  void rejectsMalformedDecimalValues(String value) {
    assertInvalid(
        HEADER + "\n2024-01-02," + value + ",103,99,102,1", "Open must be an unquoted decimal");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2024-01-02,0,103,99,102,1",
        "2024-01-02,100,-103,99,102,1",
        "2024-01-02,100,103,0,102,1",
        "2024-01-02,100,103,99,-102,1"
      })
  void rejectsNonpositivePrices(String row) {
    assertInvalid(HEADER + "\n" + row, "must be positive");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2024-01-02,100,103,101,102,1",
        "2024-01-02,104,103,99,102,1",
        "2024-01-02,100,103,99,104,1",
        "2024-01-02,100,99,103,102,1"
      })
  void rejectsInconsistentOhlcRanges(String row) {
    assertInvalid(HEADER + "\n" + row, "CSV row 2: open and close must lie between low and high");
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "1.5", "1e3", "+1", "NaN", "", "1_000", "9223372036854775808"})
  void rejectsMalformedOrOverflowingVolume(String volume) {
    assertInvalid(HEADER + "\n2024-01-02,100,103,99,102," + volume, "Volume");
  }

  @Test
  void acceptsMaximumIntegralVolumeWithoutPrecisionLoss() throws IOException {
    assertEquals(
        Long.MAX_VALUE,
        parse(HEADER + "\n2024-01-02,100,103,99,102,9223372036854775807")
            .bars()
            .getFirst()
            .volume());
  }

  @ParameterizedTest
  @ValueSource(strings = {"-1", "0", "NaN", "1e2"})
  void rejectsInvalidPresentAdjustedClose(String adjusted) {
    assertInvalid(HEADER + ",Adj Close\n" + ROW + "," + adjusted, "CSV row 2:");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2024-01-02,100,103,99,102",
        "2024-01-02,100,103,99,102,1,",
        "2024-01-02,1,000,103,99,102,1"
      })
  void rejectsMissingOrExtraFieldsRatherThanGuessing(String row) {
    assertInvalid(HEADER + "\n" + row, "CSV row 2: expected 6 columns");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2024-01-02, 100,103,99,102,1",
        "2024-01-02,100,103,99,102,1 ",
        "2024-01-02 ,100,103,99,102,1"
      })
  void rejectsSurroundingFieldWhitespace(String row) {
    assertInvalid(HEADER + "\n" + row, "surrounding whitespace");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2024-01-02,\"100\",103,99,102,1",
        "2024-01-02,\"1,000\",103,99,102,1",
        "2024-01-02,\"100\n100\",103,99,102,1"
      })
  void rejectsQuotedAndMultilineFieldsExplicitly(String row) {
    assertInvalid(HEADER + "\n" + row, "quoted fields are unsupported");
  }

  @Test
  void rejectsDuplicateDatesWithPhysicalRowNumberAfterBlankRows() {
    assertInvalid(HEADER + "\n\n" + ROW + "\n\t\n" + ROW, "CSV row 5: dates must be unique");
  }

  @Test
  void rejectsUnsortedDatesWithoutSilentlySorting() {
    assertInvalid(
        HEADER + "\n" + ROW + "\n2024-01-01,100,103,99,102,1", "CSV row 3: dates must be unique");
  }

  @Test
  void neverClosesCallerReaderOnSuccessOrInvalidInput() throws IOException {
    TrackingReader valid = new TrackingReader(HEADER + "\n" + ROW);
    parser.parse("EXAMPLE", valid);
    assertFalse(valid.closed);

    TrackingReader invalid = new TrackingReader("incorrect header");
    assertThrows(IllegalArgumentException.class, () -> parser.parse("EXAMPLE", invalid));
    assertFalse(invalid.closed);
  }

  @Test
  void propagatesIoFailureWithoutClosingCallerReader() {
    IOException failure = new IOException("fixture failure");
    boolean[] closed = {false};
    Reader reader =
        new Reader() {
          @Override
          public int read(char[] buffer, int offset, int length) throws IOException {
            throw failure;
          }

          @Override
          public void close() {
            closed[0] = true;
          }
        };

    assertSame(failure, assertThrows(IOException.class, () -> parser.parse("EXAMPLE", reader)));
    assertFalse(closed[0]);
  }

  @Test
  void rejectsNullReaderAndInvalidSymbolsIncludingEmptyDataset() {
    assertThrows(NullPointerException.class, () -> parser.parse("EXAMPLE", null));
    assertThrows(NullPointerException.class, () -> parser.parse(null, new StringReader(HEADER)));
    assertThrows(IllegalArgumentException.class, () -> parser.parse(" ", new StringReader(HEADER)));
    assertThrows(
        IllegalArgumentException.class, () -> parser.parse(" EXAMPLE", new StringReader(HEADER)));
  }

  private HistoricalPriceSeries parse(String csv) throws IOException {
    return parser.parse("EXAMPLE", new StringReader(csv));
  }

  private void assertInvalid(String csv, String expectedMessage) {
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> parse(csv));
    assertTrue(exception.getMessage().contains(expectedMessage), exception.getMessage());
  }

  private static final class TrackingReader extends StringReader {
    private boolean closed;

    private TrackingReader(String content) {
      super(content);
    }

    @Override
    public void close() {
      closed = true;
      super.close();
    }
  }
}
