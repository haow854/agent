package com.javaee.agentservice.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.internal.InternalService;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.agentservice.tool.AgentTool;
import com.javaee.agentservice.tool.AgentToolDefinition;
import com.javaee.agentservice.tool.AgentToolRegistry;
import com.javaee.agentservice.tool.Param;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 一次批准覆盖的是一次调用和它当时的参数，不是"下一步"。 */
class AgentToolGatewayApprovalTest {

    private AgentToolGateway gateway;
    private AgentState state;

    @BeforeEach
    void setUp() {
        InternalService internalService = mock(InternalService.class);
        when(internalService.hasPermission(
                any(), any(), any(Boolean.class))).thenReturn(true);

        gateway = new AgentToolGateway();
        ReflectionTestUtils.setField(gateway, "internalService", internalService);
        ReflectionTestUtils.setField(gateway, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(gateway, "safeToolMaxAttempts", 1);
        ReflectionTestUtils.setField(gateway, "safeToolRetryBackoffMs", 0L);
        ReflectionTestUtils.setField(gateway, "maxToolResultChars", 24000);
        ReflectionTestUtils.setField(gateway, "approvalExpirySeconds", 300L);
        ReflectionTestUtils.setField(gateway, "toolRegistry",
                new AgentToolRegistry(List.of(new DestructiveTool())));

        state = AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig("goal", "test-model", "system prompt",
                        List.of("destructive_tool"), "doc-1", "all", "<workspace>", "", 6, 8),
                0L);
    }

    @Test
    void challengesACallNobodyApproved() {
        AgentToolResult result = call("doc-1", null);

        assertThat(result.isRequiresAction()).isTrue();
        assertThat(result.getData()).containsEntry("approvalReason", "MISSING");
        assertThat(result.getData().get(AgentToolGateway.APPROVAL_FINGERPRINT))
                .asString().isNotBlank();
    }

    @Test
    void runsTheCallTheGrantWasGivenFor() {
        String granted = fingerprintOf(call("doc-1", null));

        AgentToolResult result = call("doc-1", granted);

        assertThat(result.getStatus()).isEqualTo("success");
    }

    @Test
    void challengesAgainWhenTheArgumentsChanged() {
        String granted = fingerprintOf(call("doc-1", null));

        AgentToolResult result = call("doc-9", granted);

        assertThat(result.isRequiresAction()).isTrue();
        assertThat(result.getData()).containsEntry("approvalReason", "PARAMS_MISMATCH");
    }

    @Test
    void doesNotCarryAGrantAcrossUsers() {
        String granted = fingerprintOf(call("doc-1", null));
        state = AgentState.create("task-1", "session-1", 8L, state.getConfig(), 0L);

        assertThat(call("doc-1", granted).isRequiresAction()).isTrue();
    }

    private String fingerprintOf(AgentToolResult challenge) {
        return String.valueOf(
                challenge.getData().get(AgentToolGateway.APPROVAL_FINGERPRINT));
    }

    private AgentToolResult call(String documentId, String approvedFingerprint) {
        return gateway.execute(
                new AssistantMessage.ToolCall("call-1", "function", "destructive_tool",
                        "{\"documentId\":\"" + documentId + "\"}"),
                state, approvedFingerprint);
    }

    private static final class DestructiveTool implements AgentTool {

        @Override
        public AgentToolDefinition definition() {
            return AgentToolDefinition.tool("destructive_tool")
                    .desc("test tool")
                    .required("documentId", Param.string("业务文档ID"))
                    .requiresApproval()
                    .build();
        }

        @Override
        public AgentToolResult execute(String idempotencyKey,
                                       Map<String, Object> params,
                                       AgentState state) {
            return AgentToolResult.success("destructive_tool", "done", Map.of());
        }
    }
}
