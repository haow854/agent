package com.javaee.agentservice.tool;

import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.AgentToolResult;
import com.javaee.documentservice.client.CollabClient;
import com.javaee.documentservice.service.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从文档里删掉一段文本，整段被删空时该段落一并移除。
 *
 * <p>与 {@code replace_text} 分成两个工具，只是为了让审批闸门有个明确判定——
 * 否则就得靠猜 {@code new} 是不是空串。删除是这套工具里唯一会让内容消失的操作，
 * 闸门留给它；普通改写不闸，因为版本历史兜底且改动实时可见。</p>
 */
@Component
public class DeleteTextTool implements AgentTool {

    @Autowired
    private CollabClient collab;

    @Autowired
    private DocumentService documentService;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("delete_text")
                .desc("从文档中删除一段文本。old 必须逐字取自 document_read 的输出且在全文中唯一。"
                        + "删掉整段的文字时该段落本身也会被移除。")
                .requiresApproval()
                .required("documentId", Param.string("业务文档ID，必填"))
                .required("old", Param.string("要删除的原文，必须唯一"))
                .build();
    }

    /**
     * 重放是安全的：已删掉的文本匹配不上，第二次直接被拒。
     */
    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String documentId = ToolArgs.require(params, "documentId");
        String oldText = ToolArgs.require(params, "old");
        documentService.assertCanWrite(documentId, state.getUserId());

        Map<String, Object> result = collab.delete(documentId, state.getUserId(), oldText);
        if (result.get("error") != null) {
            return AgentToolResult.error("delete_text", String.valueOf(result.get("error")));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", documentId);
        data.put("deleted", oldText);
        data.put("blockRemoved", result.get("removed") != null);
        return AgentToolResult.success("delete_text", "文档内容已删除: " + documentId, data);
    }
}
