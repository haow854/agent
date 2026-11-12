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
 * 文档内的唯一文本替换，改动即刻广播给所有协作者。
 *
 * <p>因为投影是 markdown，结构标记本身也是文本，所以改标题级别、把段落变成列表项、
 * 增删一段，与改一句话是同一个操作，不需要各自的工具。</p>
 *
 * <p>{@code old} 匹配不上就是文档已经变了，替换被拒——定位和并发校验是同一件事，
 * 于是既不需要版本号也不需要段落地址。</p>
 */
@Component
public class ReplaceTextTool implements AgentTool {

    @Autowired
    private CollabClient collab;

    @Autowired
    private DocumentService documentService;

    @Override
    public AgentToolDefinition definition() {
        return AgentToolDefinition.tool("replace_text")
                .desc("把文档里的一段文本替换成新文本。old 必须逐字取自 document_read 的输出"
                        + "且在全文中唯一，否则会被拒绝。"
                        + "结构也是文本：'## 标题'→'### 标题' 改层级，'文字'→'- 文字' 变列表项，"
                        + "new 里写 '新段落\\n\\n原文' 即在原文前插入一段。删除请用 delete_text。")
                .required("documentId", Param.string("业务文档ID，必填"))
                .required("old", Param.string("要被替换的原文，必须唯一"))
                .required("new", Param.string("替换成的新文本，不能为空"))
                .build();
    }

    /**
     * 重放是安全的：第一次替换之后 old 已不在文中，第二次直接匹配失败被拒，
     * 不会叠加第二份改动。
     */
    @Override
    public AgentToolResult execute(String idempotencyKey,
                                   Map<String, Object> params,
                                   AgentState state) {
        String documentId = ToolArgs.require(params, "documentId");
        String oldText = ToolArgs.require(params, "old");
        String newText = ToolArgs.require(params, "new");
        documentService.assertCanWrite(documentId, state.getUserId());

        Map<String, Object> result = collab.replace(
                documentId, state.getUserId(), oldText, newText);
        if (result.get("error") != null) {
            return AgentToolResult.error("replace_text", String.valueOf(result.get("error")));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", documentId);
        data.put("replaced", oldText);
        return AgentToolResult.success("replace_text", "文档已更新: " + documentId, data);
    }
}
