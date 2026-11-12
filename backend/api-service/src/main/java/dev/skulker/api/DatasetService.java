package dev.skulker.api;

import dev.skulker.engine.HistoricalPriceSeries;
import dev.skulker.engine.PriceBar;
import dev.skulker.engine.csv.CsvPriceParser;
import dev.skulker.store.DatasetRecord;
import dev.skulker.store.JobStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DatasetService {
  public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
  public static final int MAX_BARS = 10000;
  private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9._-]{1,32}");
  private final JobStore store;
  private final Clock clock;
  private final CsvPriceParser parser = new CsvPriceParser();

  public DatasetService(JobStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public DatasetResponse register(String symbol, MultipartFile file) {
    if (symbol == null || !SYMBOL.matcher(symbol).matches()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "Symbol must contain 1 to 32 letters, digits, periods, underscores, or hyphens.");
    }
    if (file.getSize() > MAX_FILE_BYTES) {
      throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "CSV upload exceeds 2 MiB.");
    }
    byte[] bytes;
    try (InputStream input = file.getInputStream()) {
      bytes = input.readNBytes(MAX_FILE_BYTES + 1);
    } catch (IOException exception) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "CSV upload could not be read.");
    }
    if (bytes.length > MAX_FILE_BYTES) {
      throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "CSV upload exceeds 2 MiB.");
    }
    String csv;
    try {
      csv =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException exception) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_CONTENT, "CSV must contain valid UTF-8 text.");
    }
    // Bound decimal-token parsing work before BigDecimal construction.
    if (csv.lines().anyMatch(line -> line.length() > 512)) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_CONTENT, "CSV rows must not exceed 512 characters.");
    }
    if (csv.lines().filter(line -> !line.isBlank()).count() > MAX_BARS + 1L) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_CONTENT, "CSV must not exceed 10000 price bars.");
    }
    HistoricalPriceSeries series;
    try {
      series = parser.parse(symbol, new StringReader(csv));
    } catch (IllegalArgumentException exception) {
      throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
    } catch (IOException exception) {
      throw new IllegalStateException("In-memory CSV could not be read", exception);
    }
    if (series.bars().isEmpty()) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_CONTENT, "CSV must contain at least one price bar.");
    }
    for (PriceBar bar : series.bars()) {
      requirePricePrecision(bar.open());
      requirePricePrecision(bar.high());
      requirePricePrecision(bar.low());
      requirePricePrecision(bar.close());
      bar.adjustedClose().ifPresent(DatasetService::requirePricePrecision);
    }
    DatasetRecord dataset =
        store.saveDataset(
            symbol,
            csv,
            sha256(bytes),
            series.bars().size(),
            series.bars().getFirst().date(),
            series.bars().getLast().date(),
            clock.instant());
    return DatasetResponse.from(dataset);
  }

  public DatasetRecord requireDataset(UUID id) {
    return store
        .findDataset(id)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Dataset was not found."));
  }

  public List<DatasetResponse> list(int limit, int offset) {
    validatePage(limit, offset);
    return store.listDatasets(limit, offset).stream().map(DatasetResponse::from).toList();
  }

  static void validatePage(int limit, int offset) {
    if (limit < 1 || limit > 100 || offset < 0) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "Limit must be between 1 and 100 and offset must be nonnegative.");
    }
  }

  private static void requirePricePrecision(BigDecimal price) {
    if (price.precision() > 28 || price.scale() > 12) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "CSV prices require precision at most 28 and scale at most 12.");
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
