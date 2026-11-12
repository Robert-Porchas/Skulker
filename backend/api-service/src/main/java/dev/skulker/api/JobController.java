package dev.skulker.api;

import dev.skulker.engine.backtest.BacktestResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
@Validated
public class JobController {
  private final JobService jobs;

  public JobController(JobService jobs) {
    this.jobs = jobs;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<JobResponse> submit(@Valid @RequestBody SubmitJobRequest request) {
    JobResponse response = jobs.submit(request);
    return ResponseEntity.accepted()
        .location(URI.create("/api/jobs/" + response.id()))
        .body(response);
  }

  @GetMapping
  public List<JobResponse> list(
      @RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(100) int limit,
      @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset) {
    return jobs.list(limit, offset);
  }

  @GetMapping("/{id}")
  public JobResponse get(@PathVariable("id") UUID id) {
    return JobResponse.from(jobs.requireJob(id));
  }

  @GetMapping("/{id}/result")
  public BacktestResult result(@PathVariable("id") UUID id) {
    return jobs.result(id);
  }
}
