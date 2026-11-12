package com.javaee.agentservice.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** API DTO projected from a {@link Checkpoint}. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentTaskResponse {

    private String status;
    private String phase;
    private String taskId;
    private String sessionId;
    private String task;
    private String documentId;
    private String answer;
    private Long userId;

    /**
     * What the run actually did, in order. Read back from the session stream
     * rather than kept on the task, so it costs a read and is only filled in
     * where a caller asked for one task.
     */
    private List<StepResponse> steps;
    private List<AgentState.Artifact> artifacts;
    private List<String> availableTools;

    private Integer iterations;
    private Integer toolCallCount;
    private Long promptTokens;
    /**
     * The part of {@code promptTokens} the provider served from its prefix
     * cache — a subset of it, not an addition to it. Reported because the two
     * together say whether the run kept re-sending a chain the provider already
     * had, which is a property of how the chain is assembled and invisible in
     * the total.
     */
    private Long cachedPromptTokens;
    private Long completionTokens;

    private String stoppedReason;
    private Long durationMs;
    private Long cancelledAt;
    private Long savedAt;

    /**
     * Everything the run is waiting on, so a caller can settle a whole batch in
     * one exchange. Empty unless the task is waiting.
     */
    private List<PendingResponse> pending;

    private String message;
    private String currentStatus;
    private Long retryAfterMs;

    /** One executed tool call, in the order the run performed it. */
    public record StepResponse(
            String id,
            String toolName,
            String status,
            String message,
            Long durationMs,
            boolean requiresApproval) {
    }

    /**
     * One thing the run needs from the user.
     *
     * @param kind     {@code APPROVAL} or {@code ASK_USER}
     * @param question read back from the call that asked it; the run does not
     *                 store the text, the chain already has it
     */
    public record PendingResponse(
            String kind,
            String toolCallId,
            String toolName,
            Long expiresAt,
            String question,
            Map<String, Object> params) {
    }
}
