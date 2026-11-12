package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.vo.DocumentVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Puts a document back to one of its earlier versions.
 *
 * <p>Destructive, and so behind an approval, even though the document service
 * saves the current text as a new version before overwriting it. What makes it
 * one is not that the content is unrecoverable — it is that this replaces a
 * whole document for everyone reading it, on the agent's initiative. That is a
 * call for the user to confirm, and confirming costs one click.</p>
 *
 * <p>Restoring is idempotent in the way that matters to a replayed run: the
 * target version decides the result, so running it twice lands on the same
 * text. It does leave a second entry in the version history.</p>
 */
@Component
public class DocumentRestoreTool implements AgentTool {

    @Autowired
    private DocumentService documentService;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("document_restore")
                .desc("将业务文档恢复到指定历史版本。"
                        + "版本号来自 document_version_list，不要猜测。")
                .requiresApproval()
                .required("documentId", Param.string("业务文档ID，必填"))
                .required("versionNumber",
                        Param.integer("目标版本号，来自 document_version_list").range(1, 100000))
                .build();
    }

    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String documentId = ToolArgs.require(params, "documentId");
        int versionNumber = ToolArgs.intValue(params.get("versionNumber"), 0);
        DocumentVO restored = documentService.restoreVersion(
                documentId, versionNumber, state.getUserId());

        String content = restored.getContent() == null ? "" : restored.getContent();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", restored.getId());
        data.put("restoredFrom", versionNumber);
        data.put("documentVersion", restored.getVersion());
        data.put("documentTitle", restored.getTitle());
        data.put("contentLength", content.length());
        return AgentToolResult.success("document_restore",
                "文档已恢复到版本 " + versionNumber, data);
    }
}
