package dev.skulker.domain;

/** Stable routing names shared by publisher and worker adapters. */
public final class RabbitNames {
  public static final String TASK_EXCHANGE = "skulker.tasks";
  public static final String TASK_QUEUE = "skulker.backtests";
  public static final String TASK_ROUTING_KEY = "backtest.execute";
  public static final String DEAD_EXCHANGE = "skulker.dead";
  public static final String DEAD_QUEUE = "skulker.backtests.dead";
  public static final String DEAD_ROUTING_KEY = "backtest.failed";

  private RabbitNames() {}
}
