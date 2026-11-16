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

class WebFetchToolTest {

    private final McpConnections connections = mock(McpConnections.class);
    private final McpSyncClient client = mock(McpSyncClient.class);
    private final WebFetchTool tool = new WebFetchTool();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(tool, "connections", connections);
        when(connections.search()).thenReturn(client);
    }

    @Test
    void declaresUrlAndPagingParamsAndCarriesNoRisk() {
        AgentToolDefinition definition = tool.definition();

        assertThat(definition.getName()).isEqualTo("web_fetch");
        assertThat(definition.getParameterSchema())
                .containsOnlyKeys("url", "max_length", "start_index");
        assertThat(definition.getParameterSchema().get("url").isRequired()).isTrue();
        assertThat(definition.isRequiresApproval()).isFalse();
    }

    /** 网页交给 Tavily 下载，本机不发请求。 */
    @Test
    void asksTavilyToExtractThePage() {
        when(client.callTool(any())).thenReturn(result(page("# 正文"), false));

        fetch(Map.of("url", "https://example.com/post"));

        ArgumentCaptor<McpSchema.CallToolRequest> request =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(request.capture());
        assertThat(request.getValue().name()).isEqualTo("tavily_extract");
        assertThat(request.getValue().arguments()).containsEntry("urls", List.of("https://example.com/post"));
    }

    @Test
    void returnsThePageInPieces() {
        when(client.callTool(any())).thenReturn(result(page("0123456789abcdefghij"), false));

        Map<String, Object> first = fetch(Map.of("url", "https://example.com/post", "max_length", 8)).getData();
        Map<String, Object> last = fetch(Map.of("url", "https://example.com/post", "max_length", 8, "start_index", 16)).getData();

        assertThat(first)
                .containsEntry("title", "示例")
                .containsEntry("content", "01234567")
                .containsEntry("totalLength", 20)
                .containsEntry("nextStartIndex", 8);
        assertThat(last).containsEntry("content", "ghij").doesNotContainKey("nextStartIndex");
    }

    /** 读不到的网页 Tavily 不标记出错：结果里没有 results，原因在 failed_results 或 error 里。 */
    @Test
    void pagesTavilyCouldNotReadAreErrors() {
        String failed = "{\"results\":[],\"failed_results\":[{\"url\":\"https://example.com/x\",\"error\":\"timeout\"}]}";
        String rejected = "{\"error\":\"Extract failed\",\"status\":400,\"detail\":{\"error\":\"All URLs failed validation.\"}}";
        for (String body : List.of(failed, rejected)) {
            when(client.callTool(any())).thenReturn(result(body, false));

            AgentToolResult result = fetch(Map.of("url", "https://example.com/x"));

            assertThat(result.getStatus()).as(body).isEqualTo("error");
            assertThat(result.getMessage()).as(body).contains(body);
        }
    }

    @Test
    void reportsAnMcpFailureOrAMissingConnectionAsAnError() {
        when(client.callTool(any())).thenReturn(result("rate limited", true));
        assertThat(fetch(Map.of("url", "https://example.com/x")).getMessage()).contains("rate limited");

        when(connections.search()).thenThrow(new IllegalStateException("联网搜索和网页读取未接入"));
        assertThat(fetch(Map.of("url", "https://example.com/x")).getMessage()).contains("未接入");
    }

    private String page(String content) {
        return "{\"results\":[{\"url\":\"https://example.com/post\",\"title\":\"示例\",\"raw_content\":\""
                + content + "\",\"images\":[]}],\"failed_results\":[]}";
    }

    private McpSchema.CallToolResult result(String text, boolean error) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), error);
    }

    private AgentToolResult fetch(Map<String, Object> params) {
        return tool.execute("task-1:call-1", params, state());
    }

    private AgentState state() {
        return AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig("goal", "test-model", "system prompt",
                        List.of("web_fetch"), "doc-1", "all", "<workspace>", "", 6, 8),
                0L);
    }
}
