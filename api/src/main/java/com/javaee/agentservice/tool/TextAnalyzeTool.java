package com.javaee.agentservice.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.service.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Counts characters, words and lines of a document.
 */
@Component
public class TextAnalyzeTool implements AgentTool {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("text_analyze")
                .desc("统计业务文档正文的字符、中文、英文、数字、空格、标点、段落数。")
                .required("documentId", Param.string("业务文档ID，必填"))
                .build();
    }

    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String documentId = ToolArgs.require(params, "documentId");
        return AgentToolResult.success("text_analyze", "文本分析完成: " + documentId,
                toMap(documentService.getStats(documentId, state.getUserId())));
    }

    private Map<String, Object> toMap(Object value) {
        return objectMapper.convertValue(value, new TypeReference<>() { });
    }
}
