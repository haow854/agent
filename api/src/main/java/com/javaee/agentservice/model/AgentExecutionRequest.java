package com.javaee.agentservice.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified request for the product-level Agent execution chain.
 */
@Data
public class AgentExecutionRequest {

    private String task;
    private String sessionId;
    private Long userId;
    private String model;
    private String documentId;
    private String workspace = "all";
    private Boolean returnIntermediateSteps = true;
    private Map<String, Object> context = new HashMap<>();

    /**
     * The task this request carries on, for the operations that act on one
     * rather than start one. Absent means new work.
     */
    private String taskId;

    /**
     * Caller's name for this submission. Two submissions carrying the same key
     * are the same piece of work, so the second one joins the task the first
     * started instead of running it again.
     *
     * <p>The key has to name what the user asked for, not the attempt: a caller
     * that generates a fresh one per retry is telling the server these are
     * different tasks, and gets two.</p>
     */
    private String idempotencyKey;

    /**
     * The paused calls the user approved, by tool call id. A batch can stop on
     * several destructive calls at once, and the user may grant only some.
     */
    private List<String> approvedToolCallIds = new ArrayList<>();

    /** 续接时的回答，按它答复的那次调用的 id 分组；不带就是没答。 */
    private Map<String, String> userInputs = new HashMap<>();

    public void setContext(Map<String, Object> context) {
        this.context = context == null ? new HashMap<>() : new HashMap<>(context);
    }

    public void setApprovedToolCallIds(List<String> approvedToolCallIds) {
        this.approvedToolCallIds = approvedToolCallIds == null
                ? new ArrayList<>() : new ArrayList<>(approvedToolCallIds);
    }

    public void setUserInputs(Map<String, String> userInputs) {
        this.userInputs = userInputs == null
                ? new HashMap<>() : new HashMap<>(userInputs);
    }
}
