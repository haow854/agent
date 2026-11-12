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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebFetchToolTest {

    private final McpConnections connections = mock(McpConnections.class);
    private final McpSyncClient client = mock(McpSyncClient.class);
    private final WebFetchTool tool = new WebFetchTool();

    @BeforeEach
    void inject() {
        ReflectionTestUtils.setField(tool, "connections", connections);
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

    @Test
    void refusesPrivateAndNonHttpTargetsWithoutTouchingTheNetwork() {
        List<String> blocked = List.of(
                "ftp://example.com/x",
                "http://127.0.0.1/admin",
                "http://10.0.0.5/x",
                "http://192.168.1.1/router",
                "http://169.254.169.254/latest/meta-data",
                "http://[::1]/x");

        for (String url : blocked) {
            AgentToolResult result = tool.execute(
                    "task-1:call-1", Map.of("url", url), state());
            assertThat(result.getStatus()).as(url).isEqualTo("error");
        }
        verifyNoInteractions(connections);
    }

    @Test
    void fetchesAPublicAddressAndReturnsItsText() {
        when(connections.fetch()).thenReturn(client);
        when(client.callTool(any())).thenReturn(result("# 正文", false));

        AgentToolResult result = tool.execute("task-1:call-1", Map.of(
                "url", "http://93.184.216.34/page",
                "max_length", 4000,
                "start_index", 100), state());

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getData())
                .containsEntry("content", "# 正文")
                .containsEntry("startIndex", 100);

        ArgumentCaptor<McpSchema.CallToolRequest> request =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(request.capture());
        assertThat(request.getValue().name()).isEqualTo("fetch");
        assertThat(request.getValue().arguments())
                .containsEntry("url", "http://93.184.216.34/page")
                .containsEntry("max_length", 4000)
                .containsEntry("start_index", 100);
    }

    @Test
    void reportsAServerSideFailureAsAnErrorResult() {
        when(connections.fetch()).thenReturn(client);
        when(client.callTool(any())).thenReturn(result("抓取超时", true));

        AgentToolResult result = tool.execute(
                "task-1:call-1", Map.of("url", "http://93.184.216.34/page"), state());

        assertThat(result.getStatus()).isEqualTo("error");
        assertThat(result.getMessage()).contains("抓取超时");
    }

    private McpSchema.CallToolResult result(String text, boolean error) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), error);
    }

    private AgentState state() {
        return AgentState.create("task-1", "session-1", 7L,
                new AgentState.RunConfig("goal", "test-model", "system prompt",
                        List.of("web_fetch"), "doc-1", "all", "<workspace>", "", 6, 8),
                0L);
    }
}
