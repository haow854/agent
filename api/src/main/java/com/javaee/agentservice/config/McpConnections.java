package com.javaee.agentservice.config;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

@Component
public class McpConnections {

    @Value("${ai.mcp.search.streamable-url:}")
    private String searchStreamableUrl;

    @Value("${ai.mcp.search.url:}")
    private String searchUrl;

    @Value("${ai.mcp.search.command:}")
    private String searchCommand;

    @Value("${ai.mcp.search.args:}")
    private String searchArgs;

    @Value("${ai.mcp.fetch.command:uvx}")
    private String fetchCommand;

    @Value("${ai.mcp.fetch.args:mcp-server-fetch}")
    private String fetchArgs;

    @Value("${ai.mcp.request-timeout-seconds:20}")
    private long requestTimeoutSeconds;

    private McpSyncClient search;
    private McpSyncClient fetch;

    public synchronized McpSyncClient search() {
        if (search == null) {
            search = connect(searchTransport());
        }
        return search;
    }

    public synchronized McpSyncClient fetch() {
        if (fetch == null) {
            fetch = connect(stdio(fetchCommand, fetchArgs));
        }
        return fetch;
    }

    private McpClientTransport searchTransport() {
        if (!isBlank(searchStreamableUrl)) {
            String[] parts = splitUrl(searchStreamableUrl, "/mcp");
            return HttpClientStreamableHttpTransport.builder(parts[0])
                    .endpoint(parts[1])
                    .build();
        }
        if (!isBlank(searchUrl)) {
            String[] parts = splitUrl(searchUrl, "/sse");
            return HttpClientSseClientTransport.builder(parts[0])
                    .sseEndpoint(parts[1])
                    .build();
        }
        if (!isBlank(searchCommand)) {
            return stdio(searchCommand, searchArgs);
        }
        throw new IllegalStateException(
                "联网搜索未接入：配置 ai.mcp.search.streamable-url（远程 Streamable HTTP）、"
                        + "ai.mcp.search.url（远程 SSE）"
                        + "或 ai.mcp.search.command（本地 stdio，示例见 application.yml）");
    }

    private StdioClientTransport stdio(String command, String args) {
        ServerParameters.Builder server = ServerParameters.builder(command);
        if (!isBlank(args)) {
            server.args(args.split(","));
        }
        return new StdioClientTransport(server.build());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static String[] splitUrl(String url, String defaultEndpoint) {
        URI uri = URI.create(url.trim());
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        String endpoint = uri.getRawPath() == null || uri.getRawPath().isBlank()
                ? defaultEndpoint
                : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            endpoint = endpoint + "?" + uri.getRawQuery();
        }
        return new String[]{base, endpoint};
    }

    public static String text(McpSchema.CallToolResult result) {
        StringBuilder joined = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                if (!joined.isEmpty()) {
                    joined.append("\n");
                }
                joined.append(textContent.text());
            }
        }
        return joined.toString();
    }

    private McpSyncClient connect(McpClientTransport transport) {
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(Math.max(1, requestTimeoutSeconds)))
                .build();
        client.initialize();
        return client;
    }

    @PreDestroy
    void shutdown() {
        closeQuietly(search);
        closeQuietly(fetch);
    }

    private void closeQuietly(McpSyncClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }
}
