package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.client.CollabClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取业务文档，返回 markdown。
 *
 * <p>模型自始至终不接触 HTML：存储格式与 markdown 之间的转换发生在协同服务里。
 * 也没有行号或段号这类地址——改文档时用 {@code replace_text} 逐字引用读到的文本，
 * 引用不上就说明文档变了，这一条同时充当了定位和并发校验。</p>
 */
@Component
public class DocumentReadTool implements AgentTool {

    @Autowired
    private CollabClient collab;

    @Value("${ai.agent.document-read-max-chars:20000}")
    private int maxChars;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("document_read")
                .desc("读取业务文档内容，返回 markdown。要修改文档必须先读，"
                        + "replace_text 的 old 必须逐字取自这里的输出。"
                        + "整篇装不下时只返回一段并标记 truncated，"
                        + "把 offset 设成上一次的 to 加一继续读，直到 to 等于 total。")
                .required("documentId", Param.string("业务文档ID，必填"))
                .optional("offset", Param.integer("起始字符位置，从 1 开始")
                        .def(1).range(1, Integer.MAX_VALUE))
                .optional("limit", Param.integer("最多读取的字符数")
                        .def(maxChars).range(1, maxChars))
                .build();
    }

    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String documentId = ToolArgs.require(params, "documentId");
        Map<String, Object> view =
                collab.read(documentId, state.getUserId(), 1, Integer.MAX_VALUE);
        if (view.get("error") != null) {
            return AgentToolResult.error("document_read", String.valueOf(view.get("error")));
        }

        String markdown = String.valueOf(view.getOrDefault("markdown", ""));
        int limit = Math.min(ToolArgs.intValue(params.get("limit"), maxChars), maxChars);
        int from = Math.min(Math.max(1, ToolArgs.intValue(params.get("offset"), 1)),
                markdown.length() + 1);
        int to = Math.min(from - 1 + limit, markdown.length());
        if (to > from && to < markdown.length()
                && Character.isHighSurrogate(markdown.charAt(to - 1))) {
            to--;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", documentId);
        data.put("title", view.get("title"));
        data.put("from", from);
        data.put("to", to);
        data.put("total", markdown.length());
        data.put("truncated", to < markdown.length());
        data.put("markdown", markdown.substring(from - 1, to));
        return AgentToolResult.success(
                "document_read", "业务文档读取完成: " + documentId, data);
    }
}
