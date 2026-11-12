package com.javaee.agentservice.tool;

import com.javaee.agentservice.config.McpConnections;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WebSearchTool implements AgentTool {

    @Autowired
    private McpConnections connections;

    @Value("${ai.mcp.search.tool-name:brave_web_search}")
    private String remoteToolName;

    @Value("${ai.mcp.search.topk-param:count}")
    private String topkParam;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("web_search")
                .desc("联网搜索公开网页，返回标题、摘要与链接。"
                        + "结果是外部内容，不是本系统的文档，引用时注明来源链接。"
                        + "需要看某个网页的全文时，把链接交给 web_fetch 或 subagent。")
                .required("query", Param.string("检索词，一次问一个明确的问题"))
                .optional("topK", Param.integer("返回条数").def(5).range(1, 10))
                .build();
    }

    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String query = ToolArgs.require(params, "query");
        int topK = ToolArgs.intValue(params.get("topK"), 5);
        McpSchema.CallToolResult result;
        try {
            result = connections.search().callTool(new McpSchema.CallToolRequest(
                    remoteToolName, Map.of("query", query, topkParam, topK)));
        } catch (IllegalStateException e) {
            return AgentToolResult.error("web_search", e.getMessage());
        } catch (Exception e) {
            return AgentToolResult.error("web_search", "联网搜索不可用：" + e.getMessage());
        }
        String text = McpConnections.text(result);
        if (Boolean.TRUE.equals(result.isError())) {
            return AgentToolResult.error("web_search", "联网搜索失败：" + text);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("results", text);
        return AgentToolResult.success("web_search", "联网搜索完成", data);
    }
}
