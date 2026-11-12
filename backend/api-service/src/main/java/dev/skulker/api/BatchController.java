package dev.skulker.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/batches")
public class BatchController {
  private final BatchService batches;

  public BatchController(BatchService batches) {
    this.batches = batches;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<BatchResponse> submit(@Valid @RequestBody SubmitBatchRequest request) {
    BatchResponse batch = batches.submit(request);
    return ResponseEntity.created(URI.create("/api/batches/" + batch.id())).body(batch);
  }

  @GetMapping("/{id}")
  public BatchResponse get(@PathVariable("id") UUID id) {
    return batches.get(id);
  }
}
