package dev.skulker.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "skulker.jobs.local-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class LocalJobScheduler {
  private final JobRunner runner;

  public LocalJobScheduler(JobRunner runner) {
    this.runner = runner;
  }

  @Scheduled(fixedDelayString = "${skulker.jobs.poll-delay:1000}")
  public void poll() {
    runner.runOne();
  }
}
