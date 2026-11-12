package com.javaee.agentservice.llm;

import java.util.Map;

/**
 * Provider-neutral tool definition.
 */
public record ModelToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters,
        Boolean strict
) {
}
