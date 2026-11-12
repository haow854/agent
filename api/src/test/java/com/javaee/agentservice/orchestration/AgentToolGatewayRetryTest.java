package com.javaee.agentservice.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.agentservice.tool.AgentTool;
import com.javaee.agentservice.tool.AgentToolDefinition;
import com.javaee.agentservice.tool.AgentToolRegistry;
import com.javaee.agentservice.tool.Param;
import com.javaee.agentservice.internal.InternalService;
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

class AgentToolGatewayRetryTest {

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
        ReflectionTestUtils.setField(gateway, "safeToolMaxAttempts", 2);
        ReflectionTestUtils.setField(gateway, "safeToolRetryBackoffMs", 0L);

        state = AgentState.create("task-1", "session-1", 1L,
                new AgentState.RunConfig(
                        "goal", "test-model", "system prompt",
                        List.of("retry_safe_tool", "asking_tool"),
                        null, "all", "<workspace>", "", 6, 8),
                0L);
    }

    @Test
    void retriesAToolOnceOnServerError() {
        StubTool tool = new StubTool("retry_safe_tool");
        withTools(tool);

        AgentToolResult result = gateway.execute(
                call("call-1", "retry_safe_tool", "{\"content\":\"hello\"}"), state, null);

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(tool.attempts).isEqualTo(2);
    }

    /**
     * Every tool is retried, because every tool can be. There used to be an
     * opt-out flag for calls that might have taken effect before reporting
     * failure — file-create was the named case, though by then it already
     * derived its file id from the call identity and so overwrote itself on a
     * replay. Each tool with a side effect now pins that promise with a test of
     * its own, which is what the flag was standing in for.
     */
    @Test
    void stopsRetryingOnceTheToolAsksForTheUser() {
        StubTool tool = new StubTool("asking_tool") {
            @Override
            public AgentToolResult execute(String idempotencyKey,
                                           Map<String, Object> params,
                                           AgentState state) {
                attempts++;
                return AgentToolResult.actionRequired(
                        "asking_tool", "which document?", Map.of());
            }
        };
        withTools(tool);

        AgentToolResult result = gateway.execute(
                call("call-2", "asking_tool", "{\"content\":\"hello\"}"), state, null);

        assertThat(result.isRequiresAction()).isTrue();
        assertThat(tool.attempts).isEqualTo(1);
    }

    private void withTools(AgentTool... tools) {
        ReflectionTestUtils.setField(
                gateway, "toolRegistry", new AgentToolRegistry(List.of(tools)));
    }

    private AssistantMessage.ToolCall call(String id, String apiName, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", apiName, arguments);
    }

    /** Fails the first attempt, succeeds afterwards, and counts how often it ran. */
    private static class StubTool implements AgentTool {

        private final String name;
        protected int attempts;

        private StubTool(String name) {
            this.name = name;
        }

        @Override
        public AgentToolDefinition definition() {
            return AgentToolDefinition.tool(name)
                    .desc("test tool")
                    .optional("content", Param.string("待处理文本"))
                    .build();
        }

        @Override
        public AgentToolResult execute(String idempotencyKey,
                                       Map<String, Object> params,
                                       AgentState state) {
            attempts++;
            return attempts == 1
                    ? AgentToolResult.error(name, "temporary")
                    : AgentToolResult.success(name, "done", Map.of());
        }
    }
}
