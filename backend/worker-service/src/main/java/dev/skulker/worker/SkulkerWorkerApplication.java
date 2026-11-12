package dev.skulker.worker;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = {"dev.skulker.worker", "dev.skulker.store", "dev.skulker.messaging"})
@EntityScan("dev.skulker.store")
@EnableJpaRepositories("dev.skulker.store")
@EnableScheduling
public class SkulkerWorkerApplication {
  public static void main(String[] args) {
    SpringApplication.run(SkulkerWorkerApplication.class, args);
  }

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock clock() {
    return Clock.systemUTC();
  }
}
