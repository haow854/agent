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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The floor under every tool result, including the ones not written yet.
 *
 * <p>A result goes on the message chain and stays there, resent with every
 * later turn, so an unbounded one is not one large turn but a cost paid again
 * on each of them.</p>
 */
class AgentToolGatewayCapTest {

    private static final int CAP = 1000;

    private AgentToolGateway gateway;
    private AgentState state;
    private Map<String, Object> toolData;

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
        ReflectionTestUtils.setField(gateway, "maxToolResultChars", CAP);

        toolData = new LinkedHashMap<>();
        ReflectionTestUtils.setField(gateway, "toolRegistry",
                new AgentToolRegistry(List.of(new StubTool())));

        state = AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig("goal", "test-model", "system prompt",
                        List.of("stub_tool"), null, "all", "<workspace>", "", 6, 8),
                0L);
    }

    /** A result that fits arrives exactly as the tool wrote it. */
    @Test
    void leavesAResultThatFitsAlone() {
        toolData.put("content", "短结果");

        Map<String, Object> data = execute();

        assertThat(data).containsEntry("content", "短结果")
                .doesNotContainKey("truncated");
    }

    @Test
    void cutsASingleOversizedValueAndSaysSo() {
        toolData.put("content", "字".repeat(5000));

        Map<String, Object> data = execute();

        assertThat((String) data.get("content")).hasSizeLessThanOrEqualTo(CAP + 16);
        assertThat((String) data.get("content")).endsWith("…[truncated]");
        assertThat(data).containsEntry("truncated", true);
    }

    /**
     * One budget spent across the whole result, not a limit per value. Twenty
     * passages each under a per-value cap are still twenty times that cap, and
     * search is exactly the tool that returns twenty of something.
     */
    @Test
    void spendsOneBudgetAcrossACollectionRatherThanCappingEachEntry() {
        List<Map<String, Object>> passages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            passages.add(Map.of("segmentId", "seg-" + i, "content", "字".repeat(400)));
        }
        toolData.put("results", passages);

        Map<String, Object> data = execute();

        assertThat(totalChars(data)).isLessThanOrEqualTo(CAP + 64);
        assertThat(data).containsEntry("truncated", true);
    }

    /**
     * The document window is sized under this cap, so a window the tool already
     * measured is never cut a second time. A second cut would move where the
     * content ends without moving the line numbers the run reads to continue
     * from, and the run would skip whatever fell between them.
     */
    @Test
    void doesNotCutAWindowTheToolAlreadySized() {
        toolData.put("content", "字".repeat(CAP - 200));

        Map<String, Object> data = execute();

        assertThat((String) data.get("content")).doesNotContain("truncated");
        assertThat(data).doesNotContainKey("truncated");
    }

    @SuppressWarnings("unchecked")
    private int totalChars(Object value) {
        if (value instanceof String text) {
            return text.length();
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().mapToInt(this::totalChars).sum();
        }
        if (value instanceof List<?> list) {
            return list.stream().mapToInt(this::totalChars).sum();
        }
        return 0;
    }

    private Map<String, Object> execute() {
        AgentToolResult result = gateway.execute(
                new AssistantMessage.ToolCall("call-1", "function", "stub_tool", "{}"),
                state, null);
        return result.getData();
    }

    private final class StubTool implements AgentTool {

        @Override
        public AgentToolDefinition definition() {
            return AgentToolDefinition.tool("stub_tool")
                    .desc("test tool")
                    .optional("content", Param.string("待处理文本"))
                    .build();
        }

        @Override
        public AgentToolResult execute(String idempotencyKey,
                                       Map<String, Object> params,
                                       AgentState state) {
            return AgentToolResult.success("stub_tool", "done", toolData);
        }
    }
}
