package dev.skulker.messaging;

import dev.skulker.domain.RabbitNames;
import dev.skulker.store.JobStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Broker topology and confirmed, mandatory publishing are opt-in for applications. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "skulker.messaging.enabled", havingValue = "true")
public class MessagingConfiguration {
  private static final Logger LOGGER = LoggerFactory.getLogger(MessagingConfiguration.class);

  @Bean
  Declarables backtestTopology() {
    DirectExchange tasks = new DirectExchange(RabbitNames.TASK_EXCHANGE, true, false);
    DirectExchange dead = new DirectExchange(RabbitNames.DEAD_EXCHANGE, true, false);
    Queue taskQueue =
        QueueBuilder.durable(RabbitNames.TASK_QUEUE)
            .deadLetterExchange(RabbitNames.DEAD_EXCHANGE)
            .deadLetterRoutingKey(RabbitNames.DEAD_ROUTING_KEY)
            .build();
    Queue deadQueue = QueueBuilder.durable(RabbitNames.DEAD_QUEUE).build();
    return new Declarables(
        tasks,
        dead,
        taskQueue,
        deadQueue,
        BindingBuilder.bind(taskQueue).to(tasks).with(RabbitNames.TASK_ROUTING_KEY),
        BindingBuilder.bind(deadQueue).to(dead).with(RabbitNames.DEAD_ROUTING_KEY));
  }

  @Bean
  RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
    if (!(connectionFactory instanceof CachingConnectionFactory caching)) {
      throw new IllegalStateException(
          "Confirmed publishing requires a caching RabbitMQ connection factory");
    }
    caching.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    caching.setPublisherReturns(true);
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMandatory(true);
    template.setUsePublisherConnection(true);
    template.setReturnsCallback(
        returned ->
            LOGGER
                .atWarn()
                .addKeyValue("replyCode", returned.getReplyCode())
                .log("Broker returned an unroutable message"));
    return template;
  }

  @Bean
  @ConditionalOnProperty(
      name = "skulker.messaging.scheduling-enabled",
      havingValue = "true",
      matchIfMissing = true)
  OutboxScheduler outboxScheduler(JobStore store, OutboxDispatcher dispatcher, Clock clock) {
    return new OutboxScheduler(store, dispatcher, clock);
  }
}
