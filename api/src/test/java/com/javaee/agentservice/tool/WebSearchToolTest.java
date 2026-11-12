package com.javaee.agentservice.tool;

import com.javaee.agentservice.config.McpConnections;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSearchToolTest {

    private final McpConnections connections = mock(McpConnections.class);
    private final McpSyncClient client = mock(McpSyncClient.class);
    private final WebSearchTool tool = new WebSearchTool();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(tool, "connections", connections);
        ReflectionTestUtils.setField(tool, "remoteToolName", "web-search");
        ReflectionTestUtils.setField(tool, "topkParam", "count");
    }

    @Test
    void declaresQueryAndTopKAndCarriesNoRisk() {
        AgentToolDefinition definition = tool.definition();

        assertThat(definition.getName()).isEqualTo("web_search");
        assertThat(definition.getParameterSchema()).containsOnlyKeys("query", "topK");
        assertThat(definition.getParameterSchema().get("query").isRequired()).isTrue();

        Param topK = definition.getParameterSchema().get("topK");
        assertThat(topK.getDefaultValue()).isEqualTo(5);
        assertThat(topK.getMinValue()).isEqualTo(1);
        assertThat(topK.getMaxValue()).isEqualTo(10);

        assertThat(definition.isRequiresApproval()).isFalse();
    }

    @Test
    void forwardsTheQueryToTheRemoteToolAndReturnsItsText() {
        when(connections.search()).thenReturn(client);
        when(client.callTool(any())).thenReturn(result("第一条结果", false));

        AgentToolResult result = tool.execute(
                "task-1:call-1", Map.of("query", "spring ai 版本", "topK", 3), state());

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getData()).containsEntry("results", "第一条结果");

        ArgumentCaptor<McpSchema.CallToolRequest> request =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(request.capture());
        assertThat(request.getValue().name()).isEqualTo("web-search");
        assertThat(request.getValue().arguments())
                .containsEntry("query", "spring ai 版本")
                .containsEntry("count", 3);
    }

    @Test
    void reportsAServerSideFailureAsAnErrorResult() {
        when(connections.search()).thenReturn(client);
        when(client.callTool(any())).thenReturn(result("上游超时", true));

        AgentToolResult result = tool.execute("task-1:call-1", Map.of("query", "q"), state());

        assertThat(result.getStatus()).isEqualTo("error");
        assertThat(result.getMessage()).contains("上游超时");
    }

    @Test
    void tellsTheModelWhenSearchIsNotConfigured() {
        when(connections.search()).thenThrow(
                new IllegalStateException("联网搜索未接入：需要配置 ai.mcp.search.url"));

        AgentToolResult result = tool.execute("task-1:call-1", Map.of("query", "q"), state());

        assertThat(result.getStatus()).isEqualTo("error");
        assertThat(result.getMessage()).contains("未接入");
    }

    private McpSchema.CallToolResult result(String text, boolean error) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), error);
    }

    private AgentState state() {
        return AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig("goal", "test-model", "system prompt",
                        List.of("web_search"), "doc-1", "all", "<workspace>", "", 6, 8),
                0L);
    }
}
