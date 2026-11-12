package com.javaee.agentservice.config;

/**
 * AI messaging destinations shared by the unified RabbitMQ configuration.
 */
public final class AiRabbitMQConfig {

    public static final String AI_EXCHANGE = "ai.exchange";
    public static final String AI_MODEL_REQUEST_QUEUE = "ai.model.request.queue";
    public static final String AI_MODEL_REQUEST_ROUTING_KEY = "ai.model.request";
    public static final String AI_AGENT_TASK_ROUTING_KEY = "ai.agent.task";
    public static final String AI_ALERT_ROUTING_KEY = "ai.alert";

    private AiRabbitMQConfig() {
    }
}
