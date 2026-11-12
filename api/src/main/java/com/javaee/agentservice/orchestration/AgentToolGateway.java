package com.javaee.agentservice.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.agentservice.tool.AgentTool;
import com.javaee.agentservice.tool.AgentToolDefinition;
import com.javaee.agentservice.tool.AgentToolRegistry;
import com.javaee.agentservice.tool.Param;
import com.javaee.agentservice.internal.InternalService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Server-owned validation, approval and dispatch for one model tool call.
 *
 * <p>Arguments arrive from the model and are treated as untrusted input:
 * checked against the declared schema before anything is dispatched.</p>
 */
@Component
public class AgentToolGateway {

    /** Data key carrying the fingerprint a paused run must record. */
    public static final String APPROVAL_FINGERPRINT = "paramsFingerprint";
    public static final String APPROVAL_EXPIRES_AT = "expiresAt";

    /** Sorts nested maps too; the top level is sorted by the TreeMap around it. */
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Autowired
    private AgentToolRegistry toolRegistry;
    @Autowired
    private InternalService internalService;
    @Autowired
    private ObjectMapper objectMapper;

    @Value("${ai.agent.approval-expiry-seconds:300}")
    private long approvalExpirySeconds = 300L;

    @Value("${ai.agent.safe-tool-max-attempts:2}")
    private int safeToolMaxAttempts = 2;

    @Value("${ai.agent.safe-tool-retry-backoff-ms:200}")
    private long safeToolRetryBackoffMs = 200L;

    @Value("${ai.agent.max-tool-result-chars:24000}")
    private int maxToolResultChars = 24000;

    public AgentToolResult execute(AssistantMessage.ToolCall call,
                                   AgentState state,
                                   String approvedFingerprint) {
        String toolName = call.name();
        AgentToolDefinition definition = toolRegistry.get(toolName);
        if (definition == null) {
            return AgentToolResult.error(toolName, "Unknown tool: " + toolName);
        }
        if (!state.getConfig().availableTools().contains(toolName)) {
            return AgentToolResult.error(toolName,
                    "Tool is not available to this run: " + toolName);
        }

        try {
            Map<String, Object> params = parseArguments(call.arguments());
            applyServerDefaults(definition, params);

            AgentToolResult invalid = validationError(definition, params);
            if (invalid != null) {
                return invalid;
            }

            AgentToolResult outOfScope = scopeError(toolName, params, state);
            if (outOfScope != null) {
                return outOfScope;
            }

            AgentToolResult challenge =
                    approvalChallenge(definition, params, state, approvedFingerprint);
            if (challenge != null) {
                return challenge;
            }

            if (!internalService.hasPermission(
                    toolName, state.getUserId(), definition.isRequiresApproval())) {
                return AgentToolResult.error(
                        toolName, "Permission denied for tool: " + toolName);
            }

            String idempotencyKey = idempotencyKey(state, call);
            AgentToolResult result =
                    executeWithSafeRetry(definition, idempotencyKey, params, state);
            capResult(result);
            Map<String, Object> audit = objectMapper.convertValue(
                    result, new TypeReference<Map<String, Object>>() { });
            internalService.logAudit(toolName, params, audit);
            return result;
        } catch (Exception e) {
            return AgentToolResult.error(toolName, e.getMessage());
        }
    }

    /**
     * Holds one tool result to a bounded number of characters.
     *
     * <p>A result goes on the message chain and stays there, resent with every
     * later turn of the run, so an unbounded one is not one large turn but a
     * cost paid again on each of them. Every tool that can produce a lot of text
     * bounds itself — a document read returns a window, a search returns
     * chapters — and this is the floor under all of them, including tools that
     * have not been written yet.</p>
     *
     * <p>Spends a single budget across the result in order rather than capping
     * each value on its own: twenty passages under a per-value cap are still
     * twenty times that cap. What the budget does not reach is replaced by a
     * marker, so the model reads a result that says it was cut rather than one
     * that silently ends early.</p>
     */
    private void capResult(AgentToolResult result) {
        Map<String, Object> data = result.getData();
        if (data.isEmpty()) {
            return;
        }
        CharBudget budget = new CharBudget(maxToolResultChars);
        Map<String, Object> capped = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            Object kept = budget.keepWhatFits(value);
            if (kept != null) {
                capped.put(key, kept);
            }
        });
        if (budget.spent()) {
            capped.put("truncated", true);
        }
        result.setData(capped);
    }

    /**
     * Identity a replayed call reuses so a tool with side effects can overwrite
     * its own earlier result instead of producing a second one. Scoped by task
     * because a model's call id is only unique within one turn.
     */
    private String idempotencyKey(AgentState state, AssistantMessage.ToolCall call) {
        return state.getTaskId() + ":" + call.id();
    }

    private AgentToolResult executeWithSafeRetry(AgentToolDefinition definition,
                                                 String idempotencyKey,
                                                 Map<String, Object> params,
                                                 AgentState state) {
        int maxAttempts = Math.max(1, safeToolMaxAttempts);
        AgentToolResult last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                last = toolRegistry.tool(definition.getName())
                        .execute(idempotencyKey, params, state);
            } catch (Exception e) {
                last = AgentToolResult.error(definition.getName(), e.getMessage());
            }
            if (last == null) {
                last = AgentToolResult.error(
                        definition.getName(), "Tool returned no result");
            }
            if (!"error".equals(last.getStatus())
                    || last.isRequiresAction()
                    || attempt == maxAttempts) {
                return last;
            }
            if (!backoff(attempt)) {
                return AgentToolResult.error(
                        definition.getName(), "Tool retry interrupted");
            }
        }
        return last;
    }

    private boolean backoff(int attempt) {
        try {
            Thread.sleep(Math.max(0L, safeToolRetryBackoffMs) * attempt);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Map<String, Object> parseArguments(String arguments) throws Exception {
        if (arguments == null || arguments.isBlank()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.readValue(
                arguments, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private void applyServerDefaults(
            AgentToolDefinition definition, Map<String, Object> params) {
        for (Param parameter : definition.getParameterSchema().values()) {
            if (parameter.getDefaultValue() != null) {
                params.putIfAbsent(parameter.getName(), parameter.getDefaultValue());
            }
        }
    }

    /**
     * Checks the arguments against the declared schema.
     *
     * <p>Knows no tool by name. It used to carry one exception — file-delete's
     * two interchangeable targets — which is gone now that the tool addresses
     * a document and nothing else.</p>
     */
    private AgentToolResult validationError(AgentToolDefinition definition,
                                            Map<String, Object> params) {
        List<String> missing = definition.getParameterSchema().values().stream()
                .filter(Param::isRequired)
                .filter(parameter -> isMissing(params.get(parameter.getName())))
                .map(Param::getName)
                .toList();

        Map<String, String> invalid = new LinkedHashMap<>();
        for (Param parameter : definition.getParameterSchema().values()) {
            Object value = params.get(parameter.getName());
            if (isMissing(value)) {
                continue;
            }
            if (!typeMatches(value, parameter.getType())) {
                invalid.put(parameter.getName(), "expected " + parameter.getType());
            } else if (parameter.getAllowedValues() != null
                    && !parameter.getAllowedValues().contains(value)
                    && !parameter.getAllowedValues().contains(value.toString())) {
                invalid.put(parameter.getName(),
                        "allowed values: " + parameter.getAllowedValues());
            } else if (!numberInRange(value, parameter)) {
                invalid.put(parameter.getName(), "value is outside the allowed range");
            }
        }

        if (missing.isEmpty() && invalid.isEmpty()) {
            return null;
        }
        AgentToolResult result = AgentToolResult.error(definition.getName(),
                "Invalid tool arguments. missing=" + missing + ", invalid=" + invalid);
        result.setData(Map.of(
                "missingParameters", missing,
                "invalidParameters", invalid
        ));
        return result;
    }

    private AgentToolResult scopeError(String toolName,
                                       Map<String, Object> params,
                                       AgentState state) {
        if (!"current".equals(state.getConfig().workspace())) {
            return null;
        }
        Object documentId = params.get("documentId");
        if (documentId == null
                || String.valueOf(documentId).equals(state.getConfig().documentId())) {
            return null;
        }
        return AgentToolResult.error(toolName, "This run is scoped to document "
                + state.getConfig().documentId() + " and may not touch "
                + documentId + ". Ask the user to attach that document first.");
    }

    /**
     * The challenge to send back, or null when this call may run unapproved.
     *
     * <p>批准覆盖的是一组参数：恢复后重放出来的调用对不上就重新问。</p>
     */
    private AgentToolResult approvalChallenge(AgentToolDefinition definition,
                                              Map<String, Object> params,
                                              AgentState state,
                                              String approvedFingerprint) {
        if (!definition.isRequiresApproval()) {
            return null;
        }

        String fingerprint = fingerprint(state.getUserId(), definition.getName(), params);
        if (fingerprint.equals(approvedFingerprint)) {
            return null;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(APPROVAL_FINGERPRINT, fingerprint);
        data.put(APPROVAL_EXPIRES_AT,
                System.currentTimeMillis() + approvalExpirySeconds * 1000L);
        data.put("toolName", definition.getName());
        data.put("params", params);
        data.put("approvalReason",
                approvedFingerprint == null ? "MISSING" : "PARAMS_MISMATCH");
        data.put("interactionType", "approval");
        return AgentToolResult.actionRequired(definition.getName(),
                "User approval is required before executing this tool", data);
    }

    /** Names one call: who, what tool, which arguments. */
    private String fingerprint(Long userId, String toolName, Map<String, Object> params) {
        try {
            String raw = userId + "|" + toolName + "|" + CANONICAL.writeValueAsString(
                    new TreeMap<>(params == null ? Map.of() : params));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("生成审批指纹失败", e);
        }
    }

    private boolean typeMatches(Object value, String type) {
        return switch (type == null ? "string" : type) {
            case "integer" -> value instanceof Number
                    || value.toString().matches("^-?\\d+$");
            case "boolean" -> value instanceof Boolean
                    || "true".equalsIgnoreCase(value.toString())
                    || "false".equalsIgnoreCase(value.toString());
            default -> true;
        };
    }

    private boolean numberInRange(Object value, Param parameter) {
        if (!"integer".equals(parameter.getType())) {
            return true;
        }
        long number;
        try {
            number = Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return false;
        }
        return (parameter.getMinValue() == null
                || number >= parameter.getMinValue().longValue())
                && (parameter.getMaxValue() == null
                || number <= parameter.getMaxValue().longValue());
    }

    private boolean isMissing(Object value) {
        return value == null || value.toString().isBlank()
                || "null".equalsIgnoreCase(value.toString());
    }

    /**
     * A character allowance spent across one result, in the order its values are
     * walked. Mutable and single-use: one is made per result and thrown away.
     */
    private static final class CharBudget {

        /** Truncation mark; recognisable to the model as not part of the content. */
        private static final String CUT = "…[truncated]";

        private int remaining;

        CharBudget(int chars) {
            this.remaining = chars;
        }

        boolean spent() {
            return remaining <= 0;
        }

        /**
         * @return what fits of {@code value}, or null once the allowance is gone
         *         and the caller should leave the entry out. Marking each dropped
         *         value instead would put the marker itself inside the allowance
         *         — twenty passages of two fields is forty marks, which is most
         *         of a small allowance spent saying nothing.
         */
        Object keepWhatFits(Object value) {
            if (spent()) {
                return null;
            }
            if (value instanceof String text) {
                return keepWhatFitsOf(text);
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> kept = new LinkedHashMap<>();
                map.forEach((key, item) -> {
                    Object entry = keepWhatFits(item);
                    if (entry != null) {
                        kept.put(String.valueOf(key), entry);
                    }
                });
                return kept;
            }
            if (value instanceof List<?> list) {
                List<Object> kept = new ArrayList<>(list.size());
                for (Object item : list) {
                    Object entry = keepWhatFits(item);
                    if (entry == null) {
                        break;
                    }
                    kept.add(entry);
                }
                return kept;
            }
            // Scalars are not charged for: a number or a flag cannot be what
            // makes a result large.
            return value;
        }

        private String keepWhatFitsOf(String text) {
            if (text.length() <= remaining) {
                remaining -= text.length();
                return text;
            }
            // Cut short of a character stored as two, so it does not come apart.
            int end = remaining;
            if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            String kept = text.substring(0, end);
            remaining = 0;
            return kept + CUT;
        }
    }
}
