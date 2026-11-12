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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolGatewayScopeTest {

    private AgentToolGateway gateway;

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
                new AgentToolRegistry(List.of(new ScopedTool(), new UnscopedTool())));
    }

    @Test
    void allowsAnyDocumentUnderAll() {
        assertThat(call(state("all", "doc-1"), "scoped_tool", "doc-9").getStatus())
                .isEqualTo("success");
    }

    @Test
    void refusesOtherDocumentsUnderCurrent() {
        assertThat(call(state("current", "doc-1"), "scoped_tool", "doc-1").getStatus())
                .isEqualTo("success");

        AgentToolResult refused =
                call(state("current", "doc-1"), "scoped_tool", "doc-9");
        assertThat(refused.getStatus()).isEqualTo("error");
        assertThat(refused.getMessage()).contains("doc-9").contains("doc-1");
    }

    @Test
    void allowsCallsWithoutDocumentId() {
        assertThat(call(state("current", "doc-1"), "unscoped_tool", null).getStatus())
                .isEqualTo("success");
    }

    @Test
    void rejectsCurrentWithoutDocumentId() {
        assertThatThrownBy(() -> state("current", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace current");
    }

    @Test
    void rejectsUnknownWorkspace() {
        assertThatThrownBy(() -> state("mine", "doc-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("all or current");
    }

    private AgentState state(String workspace, String documentId) {
        return AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig("goal", "test-model", "system prompt",
                        List.of("scoped_tool", "unscoped_tool"),
                        documentId, workspace, "<workspace>", "", 6, 8),
                0L);
    }

    private AgentToolResult call(AgentState state, String toolName, String documentId) {
        String arguments = documentId == null
                ? "{\"query\":\"缓存\"}"
                : "{\"documentId\":\"" + documentId + "\"}";
        return gateway.execute(
                new AssistantMessage.ToolCall("call-1", "function", toolName, arguments),
                state, null);
    }

    private static final class ScopedTool implements AgentTool {

        @Override
        public AgentToolDefinition definition() {
            return AgentToolDefinition.tool("scoped_tool")
                    .desc("test tool")
                    .required("documentId", Param.string("业务文档ID"))
                    .build();
        }

        @Override
        public AgentToolResult execute(String idempotencyKey,
                                       Map<String, Object> params,
                                       AgentState state) {
            return AgentToolResult.success("scoped_tool", "done", Map.of());
        }
    }

    private static final class UnscopedTool implements AgentTool {

        @Override
        public AgentToolDefinition definition() {
            return AgentToolDefinition.tool("unscoped_tool")
                    .desc("test tool")
                    .required("query", Param.string("查询词"))
                    .build();
        }

        @Override
        public AgentToolResult execute(String idempotencyKey,
                                       Map<String, Object> params,
                                       AgentState state) {
            return AgentToolResult.success("unscoped_tool", "done", Map.of());
        }
    }
}
