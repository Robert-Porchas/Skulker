package dev.skulker.api;

import dev.skulker.store.DatasetRecord;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/datasets")
@Validated
public class DatasetController {
  private final DatasetService datasets;

  public DatasetController(DatasetService datasets) {
    this.datasets = datasets;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<DatasetResponse> register(
      @RequestParam("symbol") String symbol, @RequestPart("file") MultipartFile file) {
    DatasetResponse result = datasets.register(symbol, file);
    return ResponseEntity.created(URI.create("/api/datasets/" + result.id())).body(result);
  }

  @GetMapping
  public List<DatasetResponse> list(
      @RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(100) int limit,
      @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset) {
    return datasets.list(limit, offset);
  }

  @GetMapping("/{id}")
  public DatasetResponse get(@PathVariable("id") UUID id) {
    return DatasetResponse.from(datasets.requireDataset(id));
  }

  @GetMapping("/{id}/csv")
  public ResponseEntity<byte[]> csv(@PathVariable("id") UUID id) {
    DatasetRecord dataset = datasets.requireDataset(id);
    byte[] bytes = dataset.csv().getBytes(StandardCharsets.UTF_8);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .contentLength(bytes.length)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(id + ".csv").build().toString())
        .body(bytes);
  }
}
