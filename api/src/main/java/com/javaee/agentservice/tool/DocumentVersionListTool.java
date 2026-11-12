package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.entity.DocumentVersion;
import com.javaee.documentservice.service.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the versions a document has, so a later call can name one.
 *
 * <p>Versions are the document's own, not the storage object's. What a user
 * means by an earlier version is a numbered revision of the document they were
 * editing, and that is what restoring one has to put back.</p>
 */
@Component
public class DocumentVersionListTool implements AgentTool {

    /** Enough of a version's text to recognise it by, never the whole body. */
    private static final int PREVIEW_CHARS = 120;

    @Autowired
    private DocumentService documentService;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("document_version_list")
                .desc("列出业务文档的历史版本，返回版本号与修改说明，不返回正文。"
                        + "用于在恢复某一版之前确定版本号。")
                .required("documentId", Param.string("业务文档ID，必填"))
                .build();
    }

    /**
     * Returns a description of each version and not its text.
     *
     * <p>Every version carries a full copy of the document. Listing ten of them
     * verbatim would put ten documents on the message chain, where the current
     * run's turns cannot be trimmed — and the model needs none of it to pick a
     * version number.</p>
     */
    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String documentId = ToolArgs.require(params, "documentId");
        List<DocumentVersion> versions =
                documentService.getVersions(documentId, state.getUserId());

        List<Map<String, Object>> listed = versions.stream()
                .map(this::describe)
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", documentId);
        data.put("versionCount", listed.size());
        data.put("versions", listed);
        return AgentToolResult.success(
                "document_version_list", "文档版本查询完成: " + documentId, data);
    }

    private Map<String, Object> describe(DocumentVersion version) {
        String content = version.getContent() == null ? "" : version.getContent();
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("versionNumber", version.getVersionNumber());
        described.put("title", version.getTitle());
        described.put("changeLog", version.getChangeLog());
        described.put("createdBy", version.getCreatedBy());
        described.put("createTime", version.getCreateTime());
        described.put("contentLength", content.length());
        described.put("contentPreview", content.length() <= PREVIEW_CHARS
                ? content
                : content.substring(0, PREVIEW_CHARS) + "…");
        return described;
    }
}
