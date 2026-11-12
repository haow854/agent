package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.dto.DocumentQueryDTO;
import com.javaee.documentservice.service.DocumentContentService;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.vo.DocumentVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentListTool implements AgentTool {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentContentService documentContentService;

    @Value("${ai.agent.document-list-max:50}")
    private int maxDocuments = 50;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("document_list")
                .desc("列出当前用户可访问的业务文档，返回 documentId 与标题，不返回正文。"
                        + "用于将用户口中的标题解析为 documentId。"
                        + "sizeBytes 是正文的 UTF-8 字节数（中文约 3 字节一个字），"
                        + "读之前据此判断代价：加起来很大就别自己读，交给 subagent。")
                .optional("keyword", Param.string("匹配标题或关键词，留空则列出全部"))
                .optional("limit", Param.integer("最多返回几条")
                        .def(20).range(1, 50))
                .build();
    }

    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String keyword = ToolArgs.asString(params.get("keyword"));
        DocumentQueryDTO query = new DocumentQueryDTO();
        query.setKeyword(ToolArgs.isBlank(keyword) ? null : keyword.trim());

        List<DocumentVO> found = documentService.search(query, state.getUserId());
        int limit = Math.min(ToolArgs.intValue(params.get("limit"), 20), maxDocuments);
        List<Map<String, Object>> listed = found.stream()
                .limit(limit)
                .map(this::describe)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("matched", found.size());
        data.put("returned", listed.size());
        data.put("documents", listed);
        return AgentToolResult.success("document_list",
                "文档列表查询完成: " + listed.size() + "/" + found.size(), data);
    }

    private Map<String, Object> describe(DocumentVO document) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("documentId", document.getId());
        described.put("title", document.getTitle());
        described.put("category", document.getCategory());
        described.put("updatedAt", document.getUpdateTime());
        long sizeBytes = documentContentService.getContentSize(
                document.getId(), document.getBucketName());
        if (sizeBytes >= 0) {
            described.put("sizeBytes", sizeBytes);
        }
        return described;
    }
}
