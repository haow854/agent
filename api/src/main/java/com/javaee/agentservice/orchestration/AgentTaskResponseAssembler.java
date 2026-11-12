package com.javaee.agentservice.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentTaskResponse;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.agentservice.model.Checkpoint;
import com.javaee.agentservice.session.ToolCall;
import com.javaee.agentservice.session.SessionMessage;
import com.javaee.agentservice.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Projects a checkpoint onto the external task API. */
@Component
public class AgentTaskResponseAssembler {

    private static final Logger log =
            LoggerFactory.getLogger(AgentTaskResponseAssembler.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SessionManager sessionManager;

    /**
     * Projection without the executed steps. Listing endpoints use this: filling
     * steps in would read one stream per task.
     */
    public AgentTaskResponse toResponse(Checkpoint checkpoint) {
        if (checkpoint == null) {
            return null;
        }
        AgentState state = checkpoint.state();
        AgentState.RunConfig config = state.getConfig();

        AgentTaskResponse response = new AgentTaskResponse();
        response.setStatus(externalStatus(state.getStatus()));
        response.setPhase(state.getPhase().name().toLowerCase());
        response.setTaskId(state.getTaskId());
        response.setSessionId(state.getSessionId());
        response.setUserId(state.getUserId());
        response.setTask(config.goal());
        response.setDocumentId(config.documentId());
        response.setAnswer(state.getAnswer());
        response.setArtifacts(state.getArtifacts());
        response.setAvailableTools(config.availableTools());
        response.setIterations(state.getBudget().iterationsUsed());
        response.setToolCallCount(state.getBudget().toolCallsUsed());
        response.setPromptTokens(state.getBudget().usage().promptTokens());
        response.setCachedPromptTokens(state.getBudget().usage().cachedPromptTokens());
        response.setCompletionTokens(state.getBudget().usage().completionTokens());
        response.setStoppedReason(state.getStoppedReason());
        response.setDurationMs(Math.max(0L, checkpoint.updatedAt() - state.getStartedAt()));
        response.setCancelledAt(state.getCancelledAt());
        response.setSavedAt(checkpoint.updatedAt());
        applyPending(response, state);
        return response;
    }

    /** Projection including the executed steps, read back from the stream. */
    public AgentTaskResponse toDetailedResponse(Checkpoint checkpoint) {
        AgentTaskResponse response = toResponse(checkpoint);
        if (response != null) {
            response.setSteps(readSteps(checkpoint.state()));
        }
        return response;
    }

    public AgentTaskResponse status(String status, String taskId, String message) {
        AgentTaskResponse response = new AgentTaskResponse();
        response.setStatus(status);
        response.setTaskId(taskId);
        response.setMessage(message);
        return response;
    }

    public Map<String, Object> toPayload(AgentTaskResponse response) {
        if (response == null) {
            return Map.of();
        }
        return objectMapper.convertValue(
                response, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    /**
     * The external vocabulary predates the internal one and callers still read
     * it, so the two are translated here rather than renamed in the state.
     */
    private String externalStatus(AgentState.TaskStatus status) {
        return switch (status) {
            case RUNNING -> "running";
            case WAITING_USER -> "action_required";
            case SUCCEEDED -> "success";
            case FAILED -> "error";
            case CANCELLED -> "cancelled";
        };
    }

    /**
     * Projects what the run is waiting on.
     *
     * <p>The fingerprint stays behind: it is the server's own check that an
     * approval still matches the call it was issued for, and a caller that could
     * see it gains nothing.</p>
     */
    private void applyPending(AgentTaskResponse response, AgentState state) {
        List<AgentState.Pending> interactions = state.getPendingInteractions();
        if (interactions.isEmpty()) {
            return;
        }
        Map<String, ToolCallDetail> asked = askedCalls(state, interactions);
        List<AgentTaskResponse.PendingResponse> pending = interactions.stream()
                .map(interaction -> {
                    ToolCallDetail detail = asked.get(interaction.toolCallId());
                    return new AgentTaskResponse.PendingResponse(
                            interaction.kind().name(),
                            interaction.toolCallId(),
                            interaction.toolName(),
                            interaction.expiresAt() == 0L
                                    ? null : interaction.expiresAt(),
                            detail == null ? null : detail.question(),
                            detail == null ? Map.of() : detail.params());
                })
                .toList();
        response.setPending(pending);
    }

    /**
     * Reads the arguments of the calls a run stopped on back off the chain.
     *
     * <p>They are not on the task: the assistant turn that made the calls
     * already carries them, and a second copy could only drift from it.</p>
     */
    private Map<String, ToolCallDetail> askedCalls(
            AgentState state, List<AgentState.Pending> interactions) {
        Set<String> wanted = interactions.stream()
                .map(AgentState.Pending::toolCallId)
                .collect(Collectors.toSet());
        Map<String, ToolCallDetail> detail = new LinkedHashMap<>();
        for (SessionMessage message : readHistory(state)) {
            if (!"assistant".equals(message.getRole()) || message.getToolCalls() == null) {
                continue;
            }
            for (ToolCall call : message.getToolCalls()) {
                if (!wanted.contains(call.id())) {
                    continue;
                }
                Map<String, Object> params = readParams(call.arguments());
                String question = stringValue(params.get("question"));
                detail.put(call.id(), new ToolCallDetail(question, params));
            }
        }
        return detail;
    }

    private Map<String, Object> readParams(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(
                    arguments, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception e) {
            log.debug("Skipping unreadable tool arguments: {}", e.getMessage());
            return Map.of();
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private record ToolCallDetail(String question, Map<String, Object> params) {
    }

    /**
     * Rebuilds the executed steps from the tool entries this task wrote. The
     * chain is the record of what ran, so nothing about it is duplicated onto
     * the task to make this cheaper.
     */
    private List<AgentTaskResponse.StepResponse> readSteps(AgentState state) {
        List<AgentTaskResponse.StepResponse> steps = new ArrayList<>();
        for (SessionMessage message : readHistory(state)) {
            if (!"tool".equals(message.getRole())
                    || !state.getTaskId().equals(message.getTaskId())) {
                continue;
            }
            AgentToolResult result = readResult(message.getContent());
            if (result == null) {
                continue;
            }
            AgentTaskResponse.StepResponse step = new AgentTaskResponse.StepResponse(
                    "step-" + (steps.size() + 1),
                    result.getToolName(),
                    result.getStatus(),
                    result.getMessage(),
                    result.getDurationMs(),
                    result.isRequiresAction());
            steps.add(step);
        }
        return steps;
    }

    /** A projection is never worth failing a read for; an unreachable stream
     * costs detail, not the response. */
    private List<SessionMessage> readHistory(AgentState state) {
        try {
            // Whole session, because the steps are picked out by task id anyway
            // — see readSteps. The context window's start is not this task's,
            // and using it as one made the projection depend on where the
            // window happened to begin.
            return sessionManager.getHistoryFrom(state.getSessionId(), null);
        } catch (Exception e) {
            log.warn("Cannot read history for task {}", state.getTaskId(), e);
            return List.of();
        }
    }

    private AgentToolResult readResult(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, AgentToolResult.class);
        } catch (Exception e) {
            log.debug("Skipping unreadable tool entry: {}", e.getMessage());
            return null;
        }
    }
}
