package com.javaee.agentservice.event;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作台实时进度事件，承载 Agent 执行状态。
 */
@Data
public class AgentProgressEvent {

    private String eventType;
    private String taskId;
    private String userId;
    private String status;
    private String message;
    private int progress;
    private long timestamp = System.currentTimeMillis();
    private Map<String, Object> payload = new LinkedHashMap<>();

    public static AgentProgressEvent of(String eventType, String userId, String status, String message) {
        AgentProgressEvent event = new AgentProgressEvent();
        event.setEventType(eventType);
        event.setUserId(userId);
        event.setStatus(status);
        event.setMessage(message);
        return event;
    }

    public void setProgress(int progress) {
        this.progress = Math.max(0, Math.min(100, progress));
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload != null ? payload : new LinkedHashMap<>();
    }
}
