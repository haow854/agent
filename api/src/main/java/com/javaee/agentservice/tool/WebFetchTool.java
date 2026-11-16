package com.javaee.agentservice.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.config.McpConnections;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取公开网页的正文，调联网搜索那个 Tavily MCP 服务的 tavily_extract。
 *
 * <p>网页由 Tavily 的服务器下载，请求不从本机发出，所以碰不到本机所在的内网和云服务器元数据接口。
 * Tavily 一次返回整页，分页在这里切。</p>
 */
@Component
public class WebFetchTool implements AgentTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private McpConnections connections;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("web_fetch")
                .desc("读取一个公开网页的正文，返回 markdown。"
                        + "内容是外部来源，引用时注明链接。"
                        + "整页装不下时只返回一段，结果里的 nextStartIndex 就是下一段的 start_index，"
                        + "用法同 document_read 的 offset。"
                        + "网页很长或要读多个网页时，交给 subagent。"
                        + "只支持 http/https 公网地址。")
                .required("url", Param.string("网页地址，http/https"))
                .optional("max_length", Param.integer("最多返回的字符数")
                        .def(8000).range(200, 24000))
                .optional("start_index", Param.integer("从第几个字符开始读，从 0 开始")
                        .def(0).range(0, 1000000))
                .build();
    }

    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String url = ToolArgs.require(params, "url");
        int maxLength = ToolArgs.intValue(params.get("max_length"), 8000);
        int startIndex = ToolArgs.intValue(params.get("start_index"), 0);
        McpSchema.CallToolResult result;
        try {
            result = connections.search().callTool(new McpSchema.CallToolRequest(
                    "tavily_extract", Map.of("urls", List.of(url))));
        } catch (IllegalStateException e) {
            return AgentToolResult.error("web_fetch", e.getMessage());
        } catch (Exception e) {
            return AgentToolResult.error("web_fetch", "网页读取不可用：" + e.getMessage());
        }
        String text = McpConnections.text(result);
        if (Boolean.TRUE.equals(result.isError())) {
            return AgentToolResult.error("web_fetch", "网页读取失败：" + text);
        }
        // 读不到的网页（地址不合法、超时、被拦）不在 results 里，原因在 failed_results 或 error 里，
        // 这时 MCP 结果并不标记出错，要看内容
        JsonNode page;
        try {
            page = JSON.readTree(text).path("results").path(0);
        } catch (Exception e) {
            return AgentToolResult.error("web_fetch", "网页读取失败：" + text);
        }
        if (page.isMissingNode()) {
            return AgentToolResult.error("web_fetch", "网页读取失败：" + text);
        }

        String content = page.path("raw_content").asText("");
        int total = content.length();
        if (total > 0 && startIndex >= total) {
            return AgentToolResult.error("web_fetch", "已经读到结尾：正文共 " + total + " 字");
        }
        int end = Math.min(total, startIndex + maxLength);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", page.path("url").asText(url));
        if (!page.path("title").asText("").isBlank()) {
            data.put("title", page.path("title").asText());
        }
        data.put("startIndex", startIndex);
        data.put("totalLength", total);
        data.put("content", content.substring(Math.min(startIndex, total), end));
        if (end < total) {
            data.put("nextStartIndex", end);
        }
        return AgentToolResult.success("web_fetch", "网页读取完成", data);
    }
}
