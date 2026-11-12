package com.javaee.agentservice.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalized result returned by every Agent tool.
 */
@Data
public class AgentToolResult {

    private String toolName;
    private String status;
    private String message;
    private boolean requiresAction;
    private long startedAt;
    private long finishedAt;
    private long durationMs;
    private Map<String, Object> data = new LinkedHashMap<>();

    public static AgentToolResult success(String toolName, String message, Map<String, Object> data) {
        AgentToolResult result = new AgentToolResult();
        result.setToolName(toolName);
        result.setStatus("success");
        result.setMessage(message);
        result.setData(data);
        return result;
    }

    public static AgentToolResult actionRequired(String toolName, String message, Map<String, Object> data) {
        AgentToolResult result = success(toolName, message, data);
        result.setStatus("action_required");
        result.setRequiresAction(true);
        return result;
    }

    public static AgentToolResult error(String toolName, String message) {
        AgentToolResult result = new AgentToolResult();
        result.setToolName(toolName);
        result.setStatus("error");
        result.setMessage(message);
        return result;
    }

    public void setData(Map<String, Object> data) {
        this.data = data != null ? new LinkedHashMap<>(data) : new LinkedHashMap<>();
    }
}
