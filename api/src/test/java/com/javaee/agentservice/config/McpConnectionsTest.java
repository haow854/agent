package com.javaee.agentservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpConnectionsTest {

    @Test
    void searchRefusesToStartWithoutAnEndpointOrACommand() {
        McpConnections connections = new McpConnections();
        ReflectionTestUtils.setField(connections, "searchStreamableUrl", "");
        ReflectionTestUtils.setField(connections, "searchUrl", "");
        ReflectionTestUtils.setField(connections, "searchCommand", "");

        assertThatThrownBy(connections::search)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ai.mcp.search.streamable-url")
                .hasMessageContaining("ai.mcp.search.url")
                .hasMessageContaining("ai.mcp.search.command");
    }

    @Test
    void splitsAnEndpointUrlIntoBaseAndPath() {
        assertThat(McpConnections.splitUrl("https://mcp.tavily.com/mcp/?tavilyApiKey=abc", "/mcp"))
                .containsExactly("https://mcp.tavily.com", "/mcp/?tavilyApiKey=abc");
        assertThat(McpConnections.splitUrl("https://mcp.example.com/sse?key=abc", "/sse"))
                .containsExactly("https://mcp.example.com", "/sse?key=abc");
        assertThat(McpConnections.splitUrl("https://mcp.example.com", "/sse"))
                .containsExactly("https://mcp.example.com", "/sse");
    }
}
