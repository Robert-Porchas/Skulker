package dev.skulker.engine.csv;

import dev.skulker.engine.HistoricalPriceSeries;
import dev.skulker.engine.PriceBar;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Strict parser for the documented, unquoted daily-price CSV schema. */
public final class CsvPriceParser {
  private static final String HEADER = "Date,Open,High,Low,Close,Volume";
  private static final String ADJUSTED_HEADER = HEADER + ",Adj Close";
  private static final Pattern DECIMAL = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");
  private static final Pattern VOLUME = Pattern.compile("[0-9]+");
  private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

  /**
   * Parses one symbol's chronologically ordered daily bars. Blank rows are skipped; malformed input
   * fails with a physical row number. The caller owns the reader, which is never closed here.
   *
   * @throws IllegalArgumentException for invalid schema, values, order, or symbol
   * @throws IOException if reading fails
   */
  public HistoricalPriceSeries parse(String symbol, Reader reader) throws IOException {
    Objects.requireNonNull(reader, "reader must not be null");
    // Validate the symbol before consuming caller input, including for empty datasets.
    new HistoricalPriceSeries(symbol, List.of());
    BufferedReader input =
        reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader);
    List<PriceBar> bars = new ArrayList<>();
    int row = 0;
    int columns = 0;
    String line;
    while ((line = input.readLine()) != null) {
      row++;
      if (columns == 0 && line.startsWith("\uFEFF")) {
        line = line.substring(1);
      }
      if (line.isBlank()) {
        continue;
      }
      if (columns == 0) {
        if (line.equals(HEADER)) {
          columns = 6;
        } else if (line.equals(ADJUSTED_HEADER)) {
          columns = 7;
        } else {
          throw invalid(row, "expected header " + HEADER + " with optional trailing ,Adj Close");
        }
        continue;
      }
      if (line.indexOf('"') >= 0) {
        throw invalid(row, "quoted fields are unsupported");
      }
      String[] fields = line.split(",", -1);
      if (fields.length != columns) {
        throw invalid(row, "expected " + columns + " columns but found " + fields.length);
      }
      for (String field : fields) {
        if (!field.equals(field.strip())) {
          throw invalid(row, "fields must not contain surrounding whitespace");
        }
      }
      LocalDate date = parseDate(fields[0], row);
      if (!bars.isEmpty() && !date.isAfter(bars.getLast().date())) {
        throw invalid(row, "dates must be unique and strictly increasing; found " + date);
      }
      BigDecimal open = parseDecimal(fields[1], "Open", row);
      BigDecimal high = parseDecimal(fields[2], "High", row);
      BigDecimal low = parseDecimal(fields[3], "Low", row);
      BigDecimal close = parseDecimal(fields[4], "Close", row);
      long volume = parseVolume(fields[5], row);
      Optional<BigDecimal> adjustedClose =
          columns == 7 && !fields[6].isEmpty()
              ? Optional.of(parseDecimal(fields[6], "Adj Close", row))
              : Optional.empty();
      try {
        bars.add(new PriceBar(symbol, date, open, high, low, close, volume, adjustedClose));
      } catch (IllegalArgumentException exception) {
        throw invalid(row, exception.getMessage(), exception);
      }
    }
    if (columns == 0) {
      throw invalid(row + 1, "missing CSV header");
    }
    return new HistoricalPriceSeries(symbol, bars);
  }

  private static LocalDate parseDate(String value, int row) {
    if (!DATE.matcher(value).matches()) {
      throw invalid(row, "Date must use yyyy-MM-dd");
    }
    try {
      LocalDate date = LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
      if (date.getYear() == 0) {
        throw invalid(row, "Date year must be between 0001 and 9999");
      }
      return date;
    } catch (DateTimeParseException exception) {
      throw invalid(row, "invalid Date: " + value, exception);
    }
  }

  private static BigDecimal parseDecimal(String value, String column, int row) {
    if (!DECIMAL.matcher(value).matches()) {
      throw invalid(row, column + " must be an unquoted decimal without grouping or exponent");
    }
    return new BigDecimal(value);
  }

  private static long parseVolume(String value, int row) {
    if (!VOLUME.matcher(value).matches()) {
      throw invalid(row, "Volume must be a nonnegative integer");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw invalid(row, "Volume exceeds the supported 64-bit integer range", exception);
    }
  }

  private static IllegalArgumentException invalid(int row, String message) {
    return new IllegalArgumentException("CSV row " + row + ": " + message);
  }

  private static IllegalArgumentException invalid(int row, String message, Exception cause) {
    return new IllegalArgumentException("CSV row " + row + ": " + message, cause);
  }
}
